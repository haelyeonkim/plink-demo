package com.plink.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.plink.model.LinkRecipient;
import com.plink.model.ProtectedLink;
import com.plink.repository.*;
import com.plink.ticket.repository.HolderRepository;
import com.plink.ticket.service.PasskeyClaim;
import com.plink.ticket.service.TicketService;
import com.yubico.webauthn.*;
import com.yubico.webauthn.data.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.util.*;

/**
 * Registering and using the passkey behind a private link.
 *
 * <p>The first passkey registered on a recipient's address claims it; nothing else is
 * asked but the link's password, if it has one. A person keeps one passkey for every link
 * and ticket they receive: somebody who already has one answers it instead of making
 * another (see {@link PasskeyClaim}), and from then on only that person's passkey opens
 * the link.
 */
@Service
public class PasskeyService {
    private static final String PENDING = "plink.passkey.pending";
    private static final long TTL = 120_000;
    static final Duration ATTEMPT_WINDOW = Duration.ofMinutes(15);

    private final LinkRepository links;
    private final LinkViewRepository views;
    private final LinkRecipientRepository recipients;
    private final com.plink.repository.ContentRepository contents;
    private final ContentImageService images;
    private final ArtworkStatusService saleStatuses;
    private final LinkService linkService;
    private final PasskeyClaim claim;
    private final ObjectMapper mapper;
    private final AttemptLimiter limiter;
    private final int maxFailuresPerCode;
    private final int maxFailuresPerClient;

    public PasskeyService(LinkRepository links, LinkViewRepository views,
            LinkRecipientRepository recipients, LinkService linkService, PasskeyClaim claim, ObjectMapper mapper,
            com.plink.repository.ContentRepository contents, ContentImageService images,
            ArtworkStatusService saleStatuses, AttemptLimiter limiter,
            @Value("${plink.links.max-failures-per-code:10}") int maxFailuresPerCode,
            @Value("${plink.links.max-failures-per-client:50}") int maxFailuresPerClient) {
        this.limiter = limiter;
        this.maxFailuresPerCode = maxFailuresPerCode;
        this.maxFailuresPerClient = maxFailuresPerClient;
        this.links = links;
        this.views = views;
        this.recipients = recipients;
        this.contents = contents;
        this.images = images;
        this.saleStatuses = saleStatuses;
        this.linkService = linkService;
        this.claim = claim;
        this.mapper = mapper;
    }

    /**
     * One ceremony in progress: a new passkey, or an assertion - of the person the link is
     * bound to ({@code holderId}), or of whoever answers, for a claim.
     */
    static class Pending {
        final String code;
        final long recipientId;
        final Long holderId;
        final long expires = System.currentTimeMillis() + TTL;
        final PasskeyClaim.NewPasskey registration;
        final AssertionRequest assertion;

        Pending(String code, long recipientId, Long holderId,
                PasskeyClaim.NewPasskey registration, AssertionRequest assertion) {
            this.code = code; this.recipientId = recipientId; this.holderId = holderId;
            this.registration = registration; this.assertion = assertion;
        }
    }

    private ResponseStatusException fail(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }

    private void checkAvailable(ProtectedLink link, LinkRecipient recipient) {
        // The recipient's own address can be switched off without touching anyone else's.
        if (recipient.revoked()) throw fail(HttpStatus.GONE, "사용할 수 없는 링크예요.");
        if (link.isExpired()) throw fail(HttpStatus.GONE, "이 링크는 만료되었어요.");
        if (link.getMaxViews() > 0 && link.getViewCount() >= link.getMaxViews()) {
            throw fail(HttpStatus.GONE, "열람 가능한 횟수를 모두 사용했어요.");
        }
    }

    private LinkRecipient recipient(String code) {
        return recipients.findByCode(code)
            .orElseThrow(() -> fail(HttpStatus.NOT_FOUND, "링크를 찾을 수 없어요."));
    }

    /**
     * Guessing is limited twice: per address issued, so one link cannot be worked at from
     * many places, and per client, so one client cannot work through many links.
     */
    private void checkAttempts(String code, String client) {
        // 0 turns the per-client cap off: behind a proxy that does not pass the visitor's
        // address on, every visitor is one client and the cap would shut them all out.
        if (limiter.blocked("link-code:" + code, maxFailuresPerCode)
                || (maxFailuresPerClient > 0 && limiter.blocked("link-client:" + client, maxFailuresPerClient))) {
            throw fail(HttpStatus.TOO_MANY_REQUESTS, "시도가 너무 많아요. 15분 뒤에 다시 시도해 주세요.");
        }
    }

    private void countFailure(String code, String client) {
        limiter.fail("link-code:" + code, ATTEMPT_WINDOW);
        if (maxFailuresPerClient > 0) limiter.fail("link-client:" + client, ATTEMPT_WINDOW);
    }

    /**
     * Chooses the ceremony. A claimed link asks its person's passkey. An unclaimed one makes
     * a new passkey, or with {@code choice} "existing" asks for one the visitor already has.
     *
     * @param client the caller's network address, for the attempt limit
     */
    public Map<String, Object> start(String code, String password, String choice, String client,
            HttpSession session) {
        // Only one outstanding ceremony per browser session. Starting again replaces the old challenge.
        synchronized (session) { session.removeAttribute(PENDING); }
        checkAttempts(code, client);
        LinkRecipient recipient = recipients.findByCode(code).orElse(null);
        if (recipient == null) {
            countFailure(code, client);
            throw fail(HttpStatus.NOT_FOUND, "링크를 찾을 수 없어요.");
        }
        ProtectedLink link = links.findById(recipient.linkId)
            .orElseThrow(() -> fail(HttpStatus.NOT_FOUND, "링크를 찾을 수 없어요."));
        checkAvailable(link, recipient);
        if (!linkService.passwordMatches(link, password)) {
            countFailure(code, client);
            throw fail(HttpStatus.FORBIDDEN, "비밀번호를 확인해 주세요.");
        }
        Pending pending;
        String options;
        try {
            if (recipient.claimed()) {
                AssertionRequest request = claim.startHolder(recipient.holderId, TTL);
                pending = new Pending(code, recipient.id, recipient.holderId, null, request);
                options = request.toCredentialsGetJson();
            } else if ("existing".equals(choice)) {
                AssertionRequest request = claim.startExisting(recipient.email, null, TTL);
                pending = new Pending(code, recipient.id, null, null, request);
                options = request.toCredentialsGetJson();
            } else {
                PasskeyClaim.NewPasskey request = claim.startNew(recipient.email, null, false, TTL);
                pending = new Pending(code, recipient.id, null, request, null);
                options = request.options().toCredentialsCreateJson();
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("mode", pending.registration != null ? "register" : "authenticate");
            result.put("options", mapper.readTree(options));
            synchronized (session) { session.setAttribute(PENDING, pending); }
            return result;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot encode passkey options", e);
        }
    }

    @Transactional
    public java.util.Map<String, Object> finish(String code, JsonNode credential, HttpSession session) {
        Pending pending;
        synchronized (session) {
            pending = (Pending) session.getAttribute(PENDING);
            session.removeAttribute(PENDING); // consume even invalid responses, before validation
        }
        if (pending == null || !pending.code.equals(code) || pending.expires <= System.currentTimeMillis()) {
            throw fail(HttpStatus.BAD_REQUEST, "인증 시간이 지났어요. 버튼을 눌러 다시 시도해 주세요.");
        }
        if (credential == null || credential.toString().length() > 65536) {
            throw fail(HttpStatus.BAD_REQUEST, "패스키 응답을 확인할 수 없어요.");
        }
        LinkRecipient before = recipient(code);
        // Lock the link row: view limits hold under concurrent requests.
        ProtectedLink link = links.lockById(before.linkId)
            .orElseThrow(() -> fail(HttpStatus.NOT_FOUND, "링크를 찾을 수 없어요."));
        // Read the recipient again under that lock. Whatever a second phone finishing at
        // the same moment did is visible now, rather than after this one has decided.
        LinkRecipient recipient = recipients.findById(before.id)
            .orElseThrow(() -> fail(HttpStatus.NOT_FOUND, "링크를 찾을 수 없어요."));
        checkAvailable(link, recipient);
        // Rebound since this ceremony started (a reissue): the person it asked for no longer holds it.
        if (pending.holderId != null && !pending.holderId.equals(recipient.holderId)) {
            throw fail(HttpStatus.CONFLICT, "이미 다른 수신자가 확정한 링크예요.");
        }

        if (pending.registration != null) {
            register(recipient, pending, credential);
        } else {
            authenticate(recipient, pending, credential);
        }

        links.incrementViewCount(link.getId());
        recipients.incrementViewCount(recipient.id);
        views.save(link.getId(), recipient.id, TicketService.mask(recipient.email));

        // Either the address the link was made for, or the document written here. The
        // document is handed over inside this response: it is never served on an address
        // of its own, so there is nothing to guess at or share.
        java.util.Map<String, Object> opened = new java.util.LinkedHashMap<>();
        opened.put("originalUrl", link.getOriginalUrl());
        if (link.getContentId() != null) {
            contents.findById(link.getContentId()).ifPresent(content -> {
                opened.put("contentTitle", content.title);
                images.grant(session, link, recipient);
                opened.put("statusEventsUrl", "/api/links/s/" + code + "/artwork-status/events");
                try { opened.put("content", saleStatuses.body(content)); }
                catch (RuntimeException unreadable) { opened.put("content", java.util.Map.of()); }
            });
        }
        return opened;
    }

    private void register(LinkRecipient recipient, Pending pending, JsonNode credential) {
        if (recipient.claimed()) throw fail(HttpStatus.CONFLICT, "이미 다른 수신자가 확정한 링크예요.");
        long holderId = claim.create(pending.registration, claim.verify(pending.registration, credential));
        // Another phone may have got there in between: then this one leaves nothing behind,
        // the throw rolling back the person and the passkey just made.
        if (recipients.bindIfUnclaimed(recipient.id, holderId) == 0) {
            throw fail(HttpStatus.CONFLICT, "이미 다른 수신자가 확정한 링크예요.");
        }
    }

    private void authenticate(LinkRecipient recipient, Pending pending, JsonNode credential) {
        HolderRepository.Credential used = claim.answer(pending.assertion, credential, "등록한 패스키로 다시 시도해 주세요.");
        if (pending.holderId != null) {
            if (used.holderId != pending.holderId) throw fail(HttpStatus.FORBIDDEN, "이 링크에 등록된 패스키가 아니에요.");
            return;
        }
        // A claim answered with a passkey the visitor already had: the link becomes that
        // person's - unless another finish bound it first, to somebody else.
        if (recipients.bindIfUnclaimed(recipient.id, used.holderId) == 0) {
            Long now = recipients.findById(recipient.id).map(row -> row.holderId).orElse(null);
            if (now == null || now != used.holderId) throw fail(HttpStatus.CONFLICT, "이미 다른 수신자가 확정한 링크예요.");
        }
    }
}
