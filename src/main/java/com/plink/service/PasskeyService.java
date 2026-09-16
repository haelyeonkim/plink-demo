package com.plink.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.plink.model.LinkRecipient;
import com.plink.model.ProtectedLink;
import com.plink.repository.*;
import com.plink.ticket.repository.HolderRepository;
import com.plink.ticket.service.TicketService;
import com.yubico.webauthn.*;
import com.yubico.webauthn.data.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.http.HttpSession;
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
    private static final long TTL = 120_000;

    private final LinkRepository links;
    private final LinkViewRepository views;
    private final LinkRecipientRepository recipients;
    private final HolderRepository holders;
    private final LinkService linkService;
    private final TicketService ticketService;
    private final RelyingParty rp;
    private final ObjectMapper mapper;

    public PasskeyService(LinkRepository links, LinkViewRepository views,
            LinkRecipientRepository recipients, HolderRepository holders, LinkService linkService,
            TicketService ticketService, RelyingParty ticketRelyingParty, ObjectMapper mapper) {
        this.links = links;
        this.views = views;
        this.recipients = recipients;
        this.holders = holders;
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

    public Map<String, Object> start(String code, String password, HttpSession session) {
        // Only one outstanding ceremony per browser session. Starting again replaces the old challenge.
        synchronized (session) { session.removeAttribute(PENDING); }
        LinkRecipient recipient = recipient(code);
        ProtectedLink link = links.findById(recipient.linkId)
            .orElseThrow(() -> fail(HttpStatus.NOT_FOUND, "링크를 찾을 수 없어요."));
        checkAvailable(link, recipient);
        if (!linkService.passwordMatches(link, password)) throw fail(HttpStatus.FORBIDDEN, "비밀번호를 확인해 주세요.");

        String email = identity(recipient);
        HolderRepository.Holder holder = holders.findByEmail(email).orElse(null);
        boolean known = holder != null && !holders.findByHolder(holder.id).isEmpty();
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

    @Transactional
    public String finish(String code, JsonNode credential, HttpSession session) {
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
        LinkRecipient recipient = recipient(code);
        // Lock the link row: view limits hold under concurrent requests.
        ProtectedLink link = links.lockById(recipient.linkId)
            .orElseThrow(() -> fail(HttpStatus.NOT_FOUND, "링크를 찾을 수 없어요."));
        checkAvailable(link, recipient);

        if (pending.registration != null) {
            register(recipient, pending, credential);
        } else {
            authenticate(recipient, pending, credential);
        }

        links.incrementViewCount(link.getId());
        recipients.incrementViewCount(recipient.id);
        views.save(link.getId(), recipient.id, TicketService.mask(pending.email));
        return link.getOriginalUrl();
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
        holders.insert(pending.holderId, result.getKeyId().getId(), result.getPublicKeyCose(),
            result.getSignatureCount(), result.getAaguid().getHex().toLowerCase(Locale.ROOT));
        recipients.bind(recipient.id, pending.holderId);
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
        if (!recipient.claimed()) {
            // A known person opening their first link: the address is theirs from now on.
            recipients.bind(recipient.id, pending.holderId);
        }
    }
}
