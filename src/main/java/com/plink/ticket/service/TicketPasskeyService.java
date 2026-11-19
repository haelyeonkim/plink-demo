package com.plink.ticket.service;

import com.plink.activity.Activity;
import com.plink.activity.ActivityLog;
import com.plink.ticket.config.TicketProperties;
import com.plink.ticket.model.Ticket;
import com.plink.ticket.model.EventSession;
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
    /** Public so the live channel can read it from the handshake's copy of the session. */
    public static final String VIEW_PREFIX = "plink.ticket.viewer.";
    /** How long one passkey check keeps a registered ticket open in this browser. */
    static final long VIEW_MILLIS = 12 * 3600_000L;
    private static final long TIMEOUT_MS = 120_000;

    private final TicketRepository tickets;
    private final HolderRepository holders;
    private final AdmissionRepository admissions;
    private final PresentationService presentations;
    private final TicketService ticketService;
    private final TicketProperties properties;
    private final RelyingParty rp;
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();

    public TicketPasskeyService(TicketRepository tickets, HolderRepository holders,
            AdmissionRepository admissions, PresentationService presentations,
            TicketService ticketService, TicketProperties properties,
            @Qualifier("ticketRelyingParty") RelyingParty rp, ObjectMapper mapper) {
        this.tickets = tickets;
        this.holders = holders;
        this.admissions = admissions;
        this.presentations = presentations;
        this.ticketService = ticketService;
        this.properties = properties;
        this.rp = rp;
        this.mapper = mapper;
    }

    static class Pending {
        final String ticketRef, direction, email, intent;
        GeoCheck.Result geo = new GeoCheck.Result(null, null, null);
        final long ticketId;
        Long holderId;
        final long expires = System.currentTimeMillis() + TIMEOUT_MS;
        final PublicKeyCredentialCreationOptions registration;
        final AssertionRequest assertion;

        Pending(Ticket ticket, String intent, String direction, String email,
                PublicKeyCredentialCreationOptions registration, AssertionRequest assertion) {
            this.ticketId = ticket.id;
            this.ticketRef = ticket.ticketRef;
            this.intent = intent;
            this.direction = direction;
            this.email = email;
            this.registration = registration;
            this.assertion = assertion;
        }
    }

    /**
     * Marks this browser as the holder's: it has just answered the ticket's passkey. A
     * registered ticket shows nothing of itself until this is true, so the link alone,
     * forwarded or opened on somebody else's phone, is not a window onto the ticket.
     */
    public void markViewer(HttpSession session, Ticket ticket) {
        session.setAttribute(VIEW_PREFIX + ticket.id,
            (System.currentTimeMillis() + VIEW_MILLIS) + "|" + binding(ticket));
    }

    public boolean canView(HttpSession session, Ticket ticket) {
        return session != null && viewerUntil(session.getAttribute(VIEW_PREFIX + ticket.id), ticket) != null;
    }

    /**
     * Who the ticket was bound to when the mark was made. Recovery rebinds it, so a phone
     * that proved the old binding - a lost one - stops seeing the ticket the moment it is
     * recovered, not twelve hours later.
     */
    static String binding(Ticket ticket) {
        return ticket.holderId + "@" + (ticket.boundAt == null ? 0 : ticket.boundAt.getTime());
    }

    /**
     * Until when a mark lets this browser see the ticket, or null when it does not. Also
     * read from a socket's copy of the session attributes.
     */
    public static Long viewerUntil(Object mark, Ticket ticket) {
        if (!(mark instanceof String value)) return null;
        int bar = value.indexOf('|');
        if (bar < 0 || !value.substring(bar + 1).equals(binding(ticket))) return null;
        try {
            long until = Long.parseLong(value.substring(0, bar));
            return until > System.currentTimeMillis() ? until : null;
        } catch (NumberFormatException broken) {
            return null;
        }
    }



    /**
     * Chooses the ceremony: an unregistered ticket registers, and its holder authenticates,
     * either to open a presentation grant or only to prove the ticket is theirs.
     */
    public Map<String, Object> start(TicketService.Resolved resolved, String intent, String rawDirection,
            HttpSession session) {
        return start(resolved, intent, rawDirection, null, session, null, null, null);
    }

    public Map<String, Object> start(TicketService.Resolved resolved, String intent, String rawDirection,
            String rawContact, HttpSession session,
            Double lat, Double lon, Double accuracy) {
        synchronized (session) { session.removeAttribute(PENDING); }
        Ticket ticket = resolved.ticket;
        boolean claimed = ticket.claimed();
        Pending pending;
        String options;

        try {
        if (!claimed) {
            String expected = ticket.issuedToEmail;
            EventSession claimSession = ticketService.requireSession(ticket.sessionId);
            // The email or the phone the ticket went to, typed back. It does not stop
            // someone determined; it stops a link forwarded by mistake being claimed by
            // whoever opened it. An organiser who hands links over in person may switch
            // it off, and then holding the link is the whole claim.
            if (!claimSession.claimSkipCheck) {
                String mismatch = TicketService.contactMismatch(rawContact, expected, ticket.phone);
                if (mismatch != null) {
                    throw new ResponseStatusException(rawContact == null || rawContact.isBlank()
                        ? HttpStatus.BAD_REQUEST : HttpStatus.FORBIDDEN, mismatch);
                }
            }
            String email = expected;
            if (ticket.claimExpiresAt != null
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
                pending = new Pending(ticket, "CLAIM", null, email, null, request);
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
                pending = new Pending(ticket, "CLAIM", null, email, request, null);
                pending.holderId = holderId;
                options = request.toCredentialsCreateJson();
            }
        } else {
            String direction = null;
            String resolvedIntent = intent == null ? "PRESENT" : intent.trim().toUpperCase(Locale.ROOT);
            if ("VIEW".equals(resolvedIntent)) {
                // Only proving it is theirs: nothing is opened, so where they are is not asked.
            } else {
                resolvedIntent = "PRESENT";
                direction = PresentationService.direction(rawDirection);
            }
            AssertionRequest request = rp.startAssertion(StartAssertionOptions.builder()
                .username(holders.findById(ticket.holderId).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.CONFLICT, "등록 정보를 찾을 수 없어요.")).email)
                .userVerification(UserVerificationRequirement.REQUIRED)
                .timeout(TIMEOUT_MS).build());
            pending = new Pending(ticket, resolvedIntent, direction, null, null, request);
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

    /**
     * Opens an entry or exit code for a holder who has already proved, on this browser,
     * that the ticket is theirs (see {@link #markViewer}). The passkey that opened the
     * ticket is what this rests on, so the code opens without asking again; where the
     * holder is and how often codes are opened are still checked, as before.
     */
    @Transactional
    public Map<String, Object> presentForViewer(Ticket ticket, String rawDirection,
            Double lat, Double lon, Double accuracy) {
        String direction = PresentationService.direction(rawDirection);
        Ticket current = tickets.lockById(ticket.id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "입장권을 찾을 수 없어요."));
        if (!current.bound()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "사용할 수 없는 입장권이에요.");
        }
        EventSession eventSession = ticketService.requireSession(current.sessionId);
        GeoCheck.Result geo = GeoCheck.evaluate(eventSession, lat, lon, accuracy);
        if ("ENFORCE".equals(eventSession.geoMode) && Boolean.FALSE.equals(geo.ok)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "공연장 근처에서만 입장 코드를 열 수 있어요.");
        }
        // The viewer mark was set by a user-verifying assertion, so the grant records it as such.
        return presentations.issue(current, direction, true, geo);
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

        String ceremony = pending.registration != null ? "register" : String.valueOf(pending.intent).toLowerCase();
        Map<String, Object> result;
        try {
            result = pending.registration != null
                ? register(locked, pending, resolved, credential)
                : authenticate(locked, pending, resolved, credential);
        } catch (ResponseStatusException refused) {
            ActivityLog.emit(Activity.of(Activity.TICKET, "passkey." + ceremony)
                .failed(String.valueOf(refused.getStatusCode().value())).session(ticket.sessionId)
                .ticket(ticket.id).summary(refused.getReason()).put("email", pending.email).always());
            throw refused;
        }
        ActivityLog.emit(Activity.of(Activity.TICKET, "passkey." + ceremony).session(ticket.sessionId)
            .ticket(ticket.id).summary(pending.email).put("email", pending.email).put("direction", pending.direction)
            .put("claimed", result.get("claimed")));
        // Whatever the ceremony was for, it proved this browser holds the ticket - as it is
        // bound now, which a claim has just changed.
        tickets.findById(ticket.id).filter(Ticket::claimed).ifPresent(bound -> markViewer(session, bound));
        return result;
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
        if (ticket.claimed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 등록이 끝난 입장권이에요.");
        }
        // Switched off between opening the page and finishing: binding would switch it
        // back on.
        if (ticket.revoked()) {
            throw new ResponseStatusException(HttpStatus.GONE, "사용할 수 없는 입장권이에요.");
        }
        if (!admissions.find(ticket.id).isPresent()) {
            admissions.create(ticket.id, ticket.sessionId);
        }
        tickets.bind(ticket.id, pending.holderId, pending.email);
        ActivityLog.emit(Activity.of(Activity.TICKET, "ticket.bound")
            .session(ticket.sessionId).ticket(ticket.id).summary(pending.email)
            .put("email", pending.email).put("ticketRef", ticket.ticketRef).put("issuedTo", ticket.issuedToEmail));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("claimed", true);
        response.put("holderEmailMasked", TicketService.mask(pending.email));
        return response;
    }

    private Map<String, Object> authenticate(Ticket ticket, Pending pending,
            TicketService.Resolved resolved, JsonNode credential) {
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
        if ("VIEW".equals(pending.intent)) {
            return Map.of("viewer", true);
        }
        if (!ticket.bound()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "사용할 수 없는 입장권이에요.");
        }
        return presentations.issue(ticket, pending.direction, true, pending.geo);
    }

}
