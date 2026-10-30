package com.plink.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.plink.model.LinkRecipient;
import com.plink.model.ProtectedLink;
import com.plink.repository.*;
import com.plink.ticket.repository.HolderRepository;
import com.plink.ticket.service.EmailOtpService;
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
 * <p>The identity is the person, exactly as on a ticket: a recipient is issued to an
 * email, and the passkey belongs to that address rather than to the link. Somebody who
 * already holds a Passlink passkey attaches this link to it instead of minting a second
 * credential, and the same passkey opens their tickets.
 */
@Service
public class PasskeyService {
    private static final String PENDING = "plink.passkey.pending";
    /** Session key prefix, per recipient: the address whose mailbox this browser proved. */
    static final String VERIFIED_PREFIX = "plink.passkey.verified.";
    private static final long TTL = 120_000;
    static final Duration ATTEMPT_WINDOW = Duration.ofMinutes(15);

    private final LinkRepository links;
    private final LinkViewRepository views;
    private final LinkRecipientRepository recipients;
    private final HolderRepository holders;
    private final com.plink.repository.ContentRepository contents;
    private final ContentImageService images;
    private final ArtworkStatusService saleStatuses;
    private final LinkService linkService;
    private final TicketService ticketService;
    private final RelyingParty rp;
    private final ObjectMapper mapper;
    private final EmailOtpService otp;
    private final AttemptLimiter limiter;
    private final int maxFailuresPerCode;
    private final int maxFailuresPerClient;

    public PasskeyService(LinkRepository links, LinkViewRepository views,
            LinkRecipientRepository recipients, HolderRepository holders, LinkService linkService,
            TicketService ticketService, RelyingParty ticketRelyingParty, ObjectMapper mapper,
            com.plink.repository.ContentRepository contents, ContentImageService images,
            ArtworkStatusService saleStatuses, EmailOtpService otp, AttemptLimiter limiter,
            @Value("${plink.links.max-failures-per-code:10}") int maxFailuresPerCode,
            @Value("${plink.links.max-failures-per-client:50}") int maxFailuresPerClient) {
        this.otp = otp;
        this.limiter = limiter;
        this.maxFailuresPerCode = maxFailuresPerCode;
        this.maxFailuresPerClient = maxFailuresPerClient;
        this.links = links;
        this.views = views;
        this.recipients = recipients;
        this.holders = holders;
        this.contents = contents;
        this.images = images;
        this.saleStatuses = saleStatuses;
        this.linkService = linkService;
        this.ticketService = ticketService;
        this.rp = ticketRelyingParty;
        this.mapper = mapper;
    }

    static class Pending {
        final String code, email;
        final long recipientId, holderId;
        final long expires = System.currentTimeMillis() + TTL;
        final PublicKeyCredentialCreationOptions registration;
        final AssertionRequest assertion;

        Pending(String code, String email, long recipientId, long holderId,
                PublicKeyCredentialCreationOptions registration, AssertionRequest assertion) {
            this.code = code; this.email = email; this.recipientId = recipientId;
            this.holderId = holderId; this.registration = registration; this.assertion = assertion;
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

    /** The address this link was issued to, or the holder's own once it is claimed. */
    private String identity(LinkRecipient recipient) {
        if (recipient.holderId != null) {
            return holders.findById(recipient.holderId)
                .orElseThrow(() -> fail(HttpStatus.CONFLICT, "등록 정보를 찾을 수 없어요.")).email;
        }
        if (recipient.email == null || recipient.email.isBlank()) {
            throw fail(HttpStatus.CONFLICT, "수신자 이메일이 없는 링크예요. 발급한 분에게 다시 요청해 주세요.");
        }
        return recipient.email;
    }

    /**
     * Matches what the visitor typed against the address the recipient was issued to.
     * Case and surrounding space are noise; everything else has to agree.
     */
    private static boolean sameContact(String issued, String given) {
        if (issued == null || given == null) return false;
        return issued.trim().equalsIgnoreCase(given.trim());
    }

    /**
     * The answer to a wrong password and to a wrong address is the same, so the form
     * cannot be used to learn which of the two somebody has right. What the page asks
     * for is public (the address page says both), so the wording may follow that.
     */
    private static String refusal(ProtectedLink link, LinkRecipient recipient) {
        boolean contact = !recipient.claimed();
        if (link.hasPassword() && contact) return "비밀번호 또는 받으신 이메일 주소가 일치하지 않아요. 다시 확인해 주세요.";
        if (link.hasPassword()) return "비밀번호를 확인해 주세요.";
        return "링크를 전달받은 이메일과 일치하지 않아요. 받으신 주소를 다시 확인해 주세요.";
    }

    private static String purpose(LinkRecipient recipient) { return "link-claim:" + recipient.id; }

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

    private static boolean emailVerified(HttpSession session, LinkRecipient recipient, String email) {
        Object proven;
        synchronized (session) { proven = session.getAttribute(VERIFIED_PREFIX + recipient.id); }
        return proven instanceof String value && value.equalsIgnoreCase(email);
    }

    /**
     * Chooses the ceremony, or asks for the mailbox first.
     *
     * <p>Answering "verify" instead of a ceremony is how the very first passkey for an
     * address is gated: until then nothing ties the address to whoever typed it, and the
     * holder this creates is the same one that later receives that person's tickets.
     *
     * @param client the caller's network address, for the attempt limit
     */
    public Map<String, Object> start(String code, String password, String contact, String client,
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
        // Before a passkey is bound, the visitor has to know the address this was sent
        // to as well as any password. Both are checked before either is reported, and a
        // failure of either reads the same.
        boolean passwordOk = linkService.passwordMatches(link, password);
        boolean contactOk = recipient.claimed() || sameContact(recipient.email, contact);
        if (!passwordOk || !contactOk) {
            countFailure(code, client);
            throw fail(HttpStatus.FORBIDDEN, refusal(link, recipient));
        }

        String email = identity(recipient);
        HolderRepository.Holder holder = holders.findByEmail(email).orElse(null);
        boolean known = holder != null && !holders.findByHolder(holder.id).isEmpty();
        if (!known && !emailVerified(session, recipient, email)) {
            // Nobody has a passkey for this address yet, so the first one made becomes
            // its owner - here and on every ticket sent to it. Typing the address is not
            // proof (whoever issued or saw the link knows it); a code sent to it is.
            boolean sent = true;
            try {
                otp.issue(email, purpose(recipient), "보호 링크 수신 확인 인증번호",
                    "보호 링크를 받으신 분이 맞는지 확인하기 위한 인증번호예요.");
            } catch (ResponseStatusException tooSoon) {
                if (tooSoon.getStatusCode().value() != HttpStatus.TOO_MANY_REQUESTS.value()) throw tooSoon;
                // A code went out less than a minute ago; that one is still good.
                sent = false;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("mode", "verify");
            result.put("sentTo", TicketService.mask(email));
            result.put("sent", sent);
            return result;
        }
        try {
            Pending pending;
            String options;
            if (known) {
                // This person already has a Passlink passkey; opening one more link asks
                // them to prove it rather than adding a second credential to their phone.
                AssertionRequest request = rp.startAssertion(StartAssertionOptions.builder()
                    .username(email)
                    .userVerification(UserVerificationRequirement.REQUIRED)
                    .timeout(TTL).build());
                pending = new Pending(code, email, recipient.id, holder.id, null, request);
                options = request.toCredentialsGetJson();
            } else {
                long holderId = holder != null ? holder.id
                    : holders.create(email, ticketService.userHandleFor(email));
                HolderRepository.Holder created = holders.findById(holderId).orElseThrow();
                PublicKeyCredentialCreationOptions request = rp.startRegistration(
                    StartRegistrationOptions.builder()
                        .user(UserIdentity.builder()
                            .name(created.email)
                            .displayName(created.email)
                            .id(HolderRepository.decode(created.userHandle)).build())
                        // PREFERRED, not REQUIRED: sign-in names this person and supplies
                        // allowCredentials, so a discoverable credential is a convenience
                        // rather than something to fail a registration over.
                        .authenticatorSelection(AuthenticatorSelectionCriteria.builder()
                            .residentKey(ResidentKeyRequirement.PREFERRED)
                            .userVerification(UserVerificationRequirement.REQUIRED).build())
                        .timeout(TTL).build());
                pending = new Pending(code, email, recipient.id, holderId, request, null);
                options = request.toCredentialsCreateJson();
            }
            Map<String, Object> result = new LinkedHashMap<>();
            // Read from the ceremony that was actually built, not from whether the link is
            // claimed: an unclaimed link asks a known person to authenticate.
            result.put("mode", pending.registration != null ? "register" : "authenticate");
            result.put("options", mapper.readTree(options));
            synchronized (session) { session.setAttribute(PENDING, pending); }
            return result;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot encode passkey options", e);
        }
    }

    /**
     * Checks the code {@link #start} mailed and remembers, for this browser only, that it
     * reached the recipient's mailbox. The next start then offers registration.
     */
    public void verifyEmail(String code, String otpCode, String client, HttpSession session) {
        checkAttempts(code, client);
        LinkRecipient recipient = recipients.findByCode(code).orElse(null);
        if (recipient == null) {
            countFailure(code, client);
            throw fail(HttpStatus.NOT_FOUND, "링크를 찾을 수 없어요.");
        }
        String email = identity(recipient);
        try {
            otp.verify(email, purpose(recipient), otpCode);
        } catch (ResponseStatusException wrong) {
            countFailure(code, client);
            throw wrong;
        }
        synchronized (session) { session.setAttribute(VERIFIED_PREFIX + recipient.id, email); }
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
        if (recipient.holderId != null && recipient.holderId != pending.holderId) {
            throw fail(HttpStatus.CONFLICT, "이미 다른 수신자가 확정한 링크예요.");
        }

        if (pending.registration != null) {
            register(recipient, pending, credential);
            synchronized (session) { session.removeAttribute(VERIFIED_PREFIX + recipient.id); }
        } else {
            authenticate(recipient, pending, credential);
        }

        links.incrementViewCount(link.getId());
        recipients.incrementViewCount(recipient.id);
        views.save(link.getId(), recipient.id, TicketService.mask(pending.email));

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
        RegistrationResult result;
        try {
            result = rp.finishRegistration(FinishRegistrationOptions.builder()
                .request(pending.registration)
                .response(PublicKeyCredential.parseRegistrationResponseJson(credential.toString()))
                .build());
        } catch (Exception invalid) {
            throw fail(HttpStatus.FORBIDDEN, "패스키 등록을 확인하지 못했어요. 다시 시도해 주세요.");
        }
        // Bind first: when another phone got there in between, this one must not leave a
        // credential on the holder either, and the throw rolls both back.
        if (recipients.bindIfUnclaimed(recipient.id, pending.holderId) == 0) {
            throw fail(HttpStatus.CONFLICT, "이미 다른 수신자가 확정한 링크예요.");
        }
        holders.insert(pending.holderId, result.getKeyId().getId(), result.getPublicKeyCose(),
            result.getSignatureCount(), result.getAaguid().getHex().toLowerCase(Locale.ROOT));
    }

    private void authenticate(LinkRecipient recipient, Pending pending, JsonNode credential) {
        AssertionResult result;
        try {
            result = rp.finishAssertion(FinishAssertionOptions.builder()
                .request(pending.assertion)
                .response(PublicKeyCredential.parseAssertionResponseJson(credential.toString()))
                .build());
        } catch (Exception invalid) {
            throw fail(HttpStatus.FORBIDDEN, "등록한 패스키로 다시 시도해 주세요.");
        }
        // The browser offers every passkey for the domain, so the asserted credential has
        // to be checked against the identity this ceremony was started for.
        HolderRepository.Credential used = holders
            .findByCredentialId(result.getCredentialId().getBase64Url())
            .orElseThrow(() -> fail(HttpStatus.FORBIDDEN, "등록되지 않은 패스키예요."));
        if (!result.isSuccess() || used.holderId != pending.holderId) {
            throw fail(HttpStatus.FORBIDDEN, "이 링크에 등록된 패스키가 아니에요.");
        }
        holders.recordUse(used.credentialId, result.getSignatureCount());
        if (!recipient.claimed() && recipients.bindIfUnclaimed(recipient.id, pending.holderId) == 0) {
            // A known person opening their first link makes the address theirs - unless
            // another finish bound it first, to somebody else.
            Long now = recipients.findById(recipient.id).map(row -> row.holderId).orElse(null);
            if (now == null || now != pending.holderId) {
                throw fail(HttpStatus.CONFLICT, "이미 다른 수신자가 확정한 링크예요.");
            }
        }
    }
}
