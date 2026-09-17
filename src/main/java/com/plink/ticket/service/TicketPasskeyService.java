package com.plink.ticket.service;

import com.plink.ticket.config.TicketProperties;
import com.plink.ticket.model.Ticket;
import com.plink.ticket.model.EventSession;
import com.plink.ticket.model.Transfer;
import com.plink.ticket.repository.AdmissionRepository;
import com.plink.ticket.repository.HolderRepository;
import com.plink.ticket.repository.TicketRepository;
import com.yubico.webauthn.*;
import com.yubico.webauthn.data.*;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Binds exactly one passkey to one ticket URL, then re-authenticates against that
 * binding on every presentation. A successful user-verifying assertion is the gate
 * that opens a presentation grant, so no QR exists before biometric confirmation.
 */
@Service
public class TicketPasskeyService {
    private static final String PENDING = "plink.ticket.passkey.pending";
    private static final String OTP_PREFIX = "plink.ticket.otp.";
    private static final long TIMEOUT_MS = 120_000;

    private final TicketRepository tickets;
    private final HolderRepository holders;
    private final AdmissionRepository admissions;
    private final PresentationService presentations;
    private final TransferService transfers;
    private final TicketService ticketService;
    private final TicketProperties properties;
    private final RelyingParty rp;
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();

    public TicketPasskeyService(TicketRepository tickets, HolderRepository holders,
            AdmissionRepository admissions, PresentationService presentations, TransferService transfers,
            TicketService ticketService, TicketProperties properties,
            @Qualifier("ticketRelyingParty") RelyingParty rp, ObjectMapper mapper) {
        this.tickets = tickets;
        this.holders = holders;
        this.admissions = admissions;
        this.presentations = presentations;
        this.transfers = transfers;
        this.ticketService = ticketService;
        this.properties = properties;
        this.rp = rp;
        this.mapper = mapper;
    }

    static class Pending {
        final String ticketRef, direction, email, intent, toEmail;
        GeoCheck.Result geo = new GeoCheck.Result(null, null, null);
        final long ticketId;
        final Long transferId;
        Long holderId;
        final long expires = System.currentTimeMillis() + TIMEOUT_MS;
        final PublicKeyCredentialCreationOptions registration;
        final AssertionRequest assertion;

        Pending(Ticket ticket, String intent, String direction, String email, String toEmail, Long transferId,
                PublicKeyCredentialCreationOptions registration, AssertionRequest assertion) {
            this.ticketId = ticket.id;
            this.ticketRef = ticket.ticketRef;
            this.intent = intent;
            this.direction = direction;
            this.email = email;
            this.toEmail = toEmail;
            this.transferId = transferId;
            this.registration = registration;
            this.assertion = assertion;
        }
    }

    /** Marks this browser session as having proved control of the ticket's email inbox. */
    public void markEmailVerified(HttpSession session, long ticketId, String email) {
        session.setAttribute(OTP_PREFIX + ticketId, email);
    }

    public Optional<String> verifiedEmail(HttpSession session, long ticketId) {
        Object value = session.getAttribute(OTP_PREFIX + ticketId);
        return Optional.ofNullable(value == null ? null : value.toString());
    }

    /**
     * Chooses the ceremony. A recipient opening a transfer link always registers, whatever
     * binding the sender still holds; the existing holder authenticates, either to open a
     * presentation grant or to sign off a transfer.
     */
    public Map<String, Object> start(TicketService.Resolved resolved, String intent, String rawDirection,
            String rawToEmail, HttpSession session) {
        return start(resolved, intent, rawDirection, rawToEmail, null, session, null, null, null);
    }

    public Map<String, Object> start(TicketService.Resolved resolved, String intent, String rawDirection,
            String rawToEmail, String rawClaimEmail, HttpSession session,
            Double lat, Double lon, Double accuracy) {
        synchronized (session) { session.removeAttribute(PENDING); }
        Ticket ticket = resolved.ticket;
        boolean claimed = !resolved.viaTransfer() && ticket.claimed();
        Pending pending;
        String options;

        try {
        if (!claimed) {
            String expected = resolved.viaTransfer() ? resolved.claim.toEmail : ticket.issuedToEmail;
            EventSession claimSession = ticketService.requireSession(ticket.sessionId);
            // With the policy off, holding the link is the whole claim. That is the
            // trade the organiser chose; the address is still recorded either way.
            Optional<String> proven = verifiedEmail(session, ticket.id)
                .filter(value -> value.equalsIgnoreCase(expected));
            if (claimSession.claimRequiresOtp) {
                proven.orElseThrow(() -> new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "이메일 인증을 먼저 완료해 주세요."));
            } else if (proven.isEmpty()) {
                // The address is typed rather than proved. That is weaker than a code and
                // is meant to be: it does not stop someone determined, it stops a link
                // that was forwarded by mistake being claimed by whoever opened it.
                String typed = EmailOtpService.normalize(rawClaimEmail == null ? "" : rawClaimEmail);
                if (typed.isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "입장권을 받은 이메일 주소를 입력해 주세요.");
                }
                if (!typed.equalsIgnoreCase(expected)) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "입장권을 받은 이메일과 일치하지 않아요. 받으신 주소를 다시 확인해 주세요.");
                }
            }
            String email = expected;
            if (!resolved.viaTransfer() && ticket.claimExpiresAt != null
                    && ticket.claimExpiresAt.toInstant().isBefore(java.time.Instant.now())) {
                throw new ResponseStatusException(HttpStatus.GONE,
                    "등록 기한이 지났어요. 새 링크를 요청해 주세요.");
            }

            HolderRepository.Holder holder = holders.findByEmail(email).orElse(null);
            boolean known = holder != null && !holders.findByHolder(holder.id).isEmpty();
            if (known) {
                // This person already has a passkey for the domain; attaching another
                // ticket asks them to prove it rather than minting a second credential.
                AssertionRequest request = rp.startAssertion(StartAssertionOptions.builder()
                    .username(email)
                    .userVerification(UserVerificationRequirement.REQUIRED)
                    .timeout(TIMEOUT_MS).build());
                pending = new Pending(ticket, "CLAIM", null, email, null,
                    resolved.viaTransfer() ? resolved.claim.id : null, null, request);
                pending.holderId = holder.id;
                options = request.toCredentialsGetJson();
            } else {
                long holderId = holder != null ? holder.id
                    : holders.create(email, ticketService.userHandleFor(email));
                AuthenticatorSelectionCriteria.AuthenticatorSelectionCriteriaBuilder selection =
                    AuthenticatorSelectionCriteria.builder()
                        .residentKey(ResidentKeyRequirement.REQUIRED)
                        .userVerification(UserVerificationRequirement.REQUIRED);
                if (!"OFF".equalsIgnoreCase(properties.getMobileOnly())) {
                    // Layer 2 of the mobile-only policy: no USB or cross-platform authenticators.
                    selection.authenticatorAttachment(AuthenticatorAttachment.PLATFORM);
                }
                HolderRepository.Holder created = holders.findById(holderId).orElseThrow();
                PublicKeyCredentialCreationOptions request = rp.startRegistration(
                    StartRegistrationOptions.builder()
                        .user(UserIdentity.builder()
                            .name(created.email)
                            .displayName(created.email)
                            .id(HolderRepository.decode(created.userHandle)).build())
                        .authenticatorSelection(selection.build())
                        .timeout(TIMEOUT_MS).build());
                pending = new Pending(ticket, "CLAIM", null, email, null,
                    resolved.viaTransfer() ? resolved.claim.id : null, request, null);
                pending.holderId = holderId;
                options = request.toCredentialsCreateJson();
            }
        } else {
            String direction = null;
            String toEmail = null;
            String resolvedIntent = intent == null ? "PRESENT" : intent.trim().toUpperCase(Locale.ROOT);
            if ("TRANSFER".equals(resolvedIntent)) {
                // The assertion is what authorises this specific handover; the recipient
                // address is held server-side with the challenge, not taken on trust later.
                toEmail = EmailOtpService.normalize(rawToEmail);
            } else {
                resolvedIntent = "PRESENT";
                direction = PresentationService.direction(rawDirection);
            }
            AssertionRequest request = rp.startAssertion(StartAssertionOptions.builder()
                .username(holders.findById(ticket.holderId).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.CONFLICT, "등록 정보를 찾을 수 없어요.")).email)
                .userVerification(UserVerificationRequirement.REQUIRED)
                .timeout(TIMEOUT_MS).build());
            pending = new Pending(ticket, resolvedIntent, direction, null, toEmail, null, null, request);
            pending.holderId = ticket.holderId;
            if ("PRESENT".equals(resolvedIntent)) {
                // Checked before the biometric prompt so a holder who is nowhere near the
                // venue is told so rather than being asked for a fingerprint first.
                EventSession eventSession = ticketService.requireSession(ticket.sessionId);
                pending.geo = GeoCheck.evaluate(eventSession, lat, lon, accuracy);
                if ("ENFORCE".equals(eventSession.geoMode) && Boolean.FALSE.equals(pending.geo.ok)) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "공연장 근처에서만 입장 코드를 열 수 있어요.");
                }
            }
            options = request.toCredentialsGetJson();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        // Read from the ceremony that was actually built, not from whether the ticket is
        // claimed: an unclaimed ticket now asks a known person to authenticate.
        result.put("mode", pending.registration != null ? "register" : "authenticate");
        result.put("intent", pending.intent);
        result.put("options", mapper.readTree(options));
        synchronized (session) { session.setAttribute(PENDING, pending); }
        return result;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot encode passkey options", e);
        }
    }

    @Transactional
    public Map<String, Object> finish(TicketService.Resolved resolved, JsonNode credential,
            HttpSession session, String ip, String userAgent) {
        Ticket ticket = resolved.ticket;
        Pending pending;
        synchronized (session) {
            pending = (Pending) session.getAttribute(PENDING);
            session.removeAttribute(PENDING); // consumed even when invalid, before validation
        }
        if (pending == null || pending.ticketId != ticket.id || pending.expires <= System.currentTimeMillis()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "인증 시간이 지났어요. 버튼을 눌러 다시 시도해 주세요.");
        }
        if (credential == null || credential.toString().length() > 65536) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "패스키 응답을 확인할 수 없어요.");
        }
        Ticket locked = tickets.lockById(ticket.id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "입장권을 찾을 수 없어요."));

        return pending.registration != null
            ? register(locked, pending, resolved, credential)
            : authenticate(locked, pending, resolved, credential, ip, userAgent);
    }

    private Map<String, Object> register(Ticket ticket, Pending pending, TicketService.Resolved resolved,
            JsonNode credential) {
        RegistrationResult result;
        try {
            result = rp.finishRegistration(FinishRegistrationOptions.builder()
                .request(pending.registration)
                .response(PublicKeyCredential.parseRegistrationResponseJson(credential.toString()))
                .build());
        } catch (Exception invalid) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "패스키 등록을 확인하지 못했어요. 다시 시도해 주세요.");
        }
        String aaguid = result.getAaguid().getHex().toLowerCase(Locale.ROOT);
        if (!properties.getAllowedAaguids().isEmpty()
                && properties.getAllowedAaguids().stream().noneMatch(allowed ->
                    allowed.replace("-", "").equalsIgnoreCase(aaguid))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "휴대폰의 지문·얼굴 인증으로 등록해 주세요. 데스크톱 인증기는 사용할 수 없어요.");
        }
        holders.insert(pending.holderId, result.getKeyId().getId(), result.getPublicKeyCose(),
            result.getSignatureCount(), aaguid);
        return attach(ticket, pending, resolved);
    }

    /** Binds the ticket to the person the ceremony proved, whichever ceremony it was. */
    private Map<String, Object> attach(Ticket ticket, Pending pending, TicketService.Resolved resolved) {
        // Re-read under the row lock: someone may have claimed the link between the
        // challenge and the response, and whoever got there first keeps the ticket.
        if (ticket.claimed() && !resolved.viaTransfer()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 등록이 끝난 입장권이에요.");
        }
        if (!admissions.find(ticket.id).isPresent()) {
            admissions.create(ticket.id, ticket.sessionId);
        }
        if (resolved.viaTransfer()) {
            transfers.accept(resolved.claim, ticket, pending.holderId);
        } else {
            tickets.bind(ticket.id, pending.holderId, pending.email);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("claimed", true);
        response.put("viaTransfer", resolved.viaTransfer());
        response.put("holderEmailMasked", TicketService.mask(pending.email));
        return response;
    }

    private Map<String, Object> authenticate(Ticket ticket, Pending pending,
            TicketService.Resolved resolved, JsonNode credential, String ip, String userAgent) {
        AssertionResult result;
        try {
            result = rp.finishAssertion(FinishAssertionOptions.builder()
                .request(pending.assertion)
                .response(PublicKeyCredential.parseAssertionResponseJson(credential.toString()))
                .build());
        } catch (Exception invalid) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "등록한 패스키로 다시 시도해 주세요.");
        }
        // The browser offers every passkey for the domain, so the asserted credential has
        // to be checked against the identity this ceremony was started for.
        HolderRepository.Credential used = holders
            .findByCredentialId(result.getCredentialId().getBase64Url())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                "등록되지 않은 패스키예요."));
        if (!result.isSuccess() || pending.holderId == null || used.holderId != pending.holderId) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "이 입장권에 등록된 패스키가 아니에요.");
        }
        holders.recordUse(used.credentialId, result.getSignatureCount());

        if ("CLAIM".equals(pending.intent)) {
            return attach(ticket, pending, resolved);
        }
        if ("TRANSFER".equals(pending.intent)) {
            return transfers.initiate(ticket, pending.toEmail, ip, userAgent);
        }
        if (!ticket.bound()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "양도가 진행 중이거나 사용할 수 없는 입장권이에요.");
        }
        return presentations.issue(ticket, pending.direction, true, pending.geo);
    }

}
