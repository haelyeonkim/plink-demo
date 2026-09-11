package com.plink.ticket.service;

import com.plink.ticket.config.TicketProperties;
import com.plink.ticket.model.Ticket;
import com.plink.ticket.repository.AdmissionRepository;
import com.plink.ticket.repository.TicketPasskeyRepository;
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
    private final TicketPasskeyRepository passkeys;
    private final AdmissionRepository admissions;
    private final PresentationService presentations;
    private final TicketProperties properties;
    private final RelyingParty rp;
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();

    public TicketPasskeyService(TicketRepository tickets, TicketPasskeyRepository passkeys,
            AdmissionRepository admissions, PresentationService presentations, TicketProperties properties,
            @Qualifier("ticketRelyingParty") RelyingParty rp, ObjectMapper mapper) {
        this.tickets = tickets;
        this.passkeys = passkeys;
        this.admissions = admissions;
        this.presentations = presentations;
        this.properties = properties;
        this.rp = rp;
        this.mapper = mapper;
    }

    static class Pending {
        final String ticketRef, direction, email;
        final long ticketId;
        final long expires = System.currentTimeMillis() + TIMEOUT_MS;
        final PublicKeyCredentialCreationOptions registration;
        final AssertionRequest assertion;

        Pending(Ticket ticket, String direction, String email,
                PublicKeyCredentialCreationOptions registration, AssertionRequest assertion) {
            this.ticketId = ticket.id;
            this.ticketRef = ticket.ticketRef;
            this.direction = direction;
            this.email = email;
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

    public Map<String, Object> start(Ticket ticket, String rawDirection, HttpSession session) {
        synchronized (session) { session.removeAttribute(PENDING); }
        boolean claimed = passkeys.findByTicketId(ticket.id).isPresent();
        Pending pending;
        String options;

        try {
        if (!claimed) {
            String email = verifiedEmail(session, ticket.id).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.FORBIDDEN, "이메일 인증을 먼저 완료해 주세요."));
            if (ticket.claimExpiresAt != null
                    && ticket.claimExpiresAt.toInstant().isBefore(java.time.Instant.now())) {
                throw new ResponseStatusException(HttpStatus.GONE,
                    "등록 기한이 지났어요. 새 링크를 요청해 주세요.");
            }
            byte[] handle = new byte[32];
            random.nextBytes(handle);
            AuthenticatorSelectionCriteria.AuthenticatorSelectionCriteriaBuilder selection =
                AuthenticatorSelectionCriteria.builder()
                    .residentKey(ResidentKeyRequirement.REQUIRED)
                    .userVerification(UserVerificationRequirement.REQUIRED);
            if (!"OFF".equalsIgnoreCase(properties.getMobileOnly())) {
                // Layer 2 of the mobile-only policy: no USB or cross-platform authenticators.
                selection.authenticatorAttachment(AuthenticatorAttachment.PLATFORM);
            }
            PublicKeyCredentialCreationOptions request = rp.startRegistration(StartRegistrationOptions.builder()
                .user(UserIdentity.builder()
                    .name(ticket.ticketRef)
                    .displayName(displayName(ticket))
                    .id(new ByteArray(handle)).build())
                .authenticatorSelection(selection.build())
                .timeout(TIMEOUT_MS).build());
            pending = new Pending(ticket, null, email, request, null);
            options = request.toCredentialsCreateJson();
        } else {
            String direction = PresentationService.direction(rawDirection);
            AssertionRequest request = rp.startAssertion(StartAssertionOptions.builder()
                .username(ticket.ticketRef)
                .userVerification(UserVerificationRequirement.REQUIRED)
                .timeout(TIMEOUT_MS).build());
            pending = new Pending(ticket, direction, null, null, request);
            options = request.toCredentialsGetJson();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", claimed ? "authenticate" : "register");
        result.put("options", mapper.readTree(options));
        synchronized (session) { session.setAttribute(PENDING, pending); }
        return result;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot encode passkey options", e);
        }
    }

    @Transactional
    public Map<String, Object> finish(Ticket ticket, JsonNode credential, HttpSession session) {
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
            ? register(locked, pending, credential)
            : authenticate(locked, pending, credential);
    }

    private Map<String, Object> register(Ticket ticket, Pending pending, JsonNode credential) {
        if (passkeys.findByTicketId(ticket.id).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 다른 기기에 등록된 입장권이에요.");
        }
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
        passkeys.insert(ticket.id, result.getKeyId().getId(), pending.registration.getUser().getId(),
            result.getPublicKeyCose(), result.getSignatureCount(), pending.email, aaguid);
        tickets.bind(ticket.id, pending.email);
        if (!admissions.find(ticket.id).isPresent()) {
            admissions.create(ticket.id, ticket.sessionId);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("claimed", true);
        response.put("holderEmailMasked", TicketService.mask(pending.email));
        return response;
    }

    private Map<String, Object> authenticate(Ticket ticket, Pending pending, JsonNode credential) {
        TicketPasskeyRepository.Binding binding = passkeys.findByTicketId(ticket.id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                "등록 정보가 변경되었어요. 페이지를 새로 고쳐 주세요."));
        AssertionResult result;
        try {
            result = rp.finishAssertion(FinishAssertionOptions.builder()
                .request(pending.assertion)
                .response(PublicKeyCredential.parseAssertionResponseJson(credential.toString()))
                .build());
        } catch (Exception invalid) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "처음 등록한 패스키로 다시 시도해 주세요.");
        }
        // The browser offers every passkey for this domain, so the asserted credential
        // must be checked against this ticket's binding rather than trusted as-is.
        if (!result.isSuccess()
                || !binding.credentialId.equals(result.getCredentialId().getBase64Url())
                || !binding.userHandle.equals(result.getUserHandle().getBase64Url())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "이 입장권에 등록된 패스키가 아니에요.");
        }
        passkeys.updateCount(ticket.id, result.getSignatureCount());
        return presentations.issue(ticket, pending.direction, true);
    }

    private String displayName(Ticket ticket) {
        StringBuilder name = new StringBuilder("입장권 ").append(ticket.ticketRef);
        if (ticket.seat != null && !ticket.seat.isEmpty()) name.append(" · ").append(ticket.seat);
        return name.toString();
    }
}
