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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
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
    private final PasskeyClaim claim;
    private final ObjectMapper mapper;

    public TicketPasskeyService(TicketRepository tickets, HolderRepository holders,
            AdmissionRepository admissions, PresentationService presentations,
            TicketService ticketService, TicketProperties properties, PasskeyClaim claim, ObjectMapper mapper) {
        this.tickets = tickets;
        this.holders = holders;
        this.admissions = admissions;
        this.presentations = presentations;
        this.ticketService = ticketService;
        this.properties = properties;
        this.claim = claim;
        this.mapper = mapper;
    }

    static class Pending {
        final String ticketRef, direction, email, intent;
        GeoCheck.Result geo = new GeoCheck.Result(null, null, null);
        final long ticketId;
        Long holderId;
        final long expires = System.currentTimeMillis() + TIMEOUT_MS;
        final PasskeyClaim.NewPasskey registration;
        final AssertionRequest assertion;

        Pending(Ticket ticket, String intent, String direction, String email,
                PasskeyClaim.NewPasskey registration, AssertionRequest assertion) {
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
     * For an unclaimed ticket, which way its page offers first: "existing" when whoever it
     * was sent to has claimed something before. Only a hint; the passkey decides.
     */
    public String suggestion(Ticket ticket) {
        return ticket.claimed() ? null : claim.suggestion(ticket.issuedToEmail, ticket.phone);
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
     * Chooses the ceremony: an unregistered ticket makes a new passkey, or with
     * {@code choice} "existing" asks for one the visitor already has; a registered ticket's
     * holder authenticates, either to open a presentation grant or only to prove it is theirs.
     */
    public Map<String, Object> start(TicketService.Resolved resolved, String intent, String rawDirection,
            HttpSession session) {
        return start(resolved, intent, rawDirection, null, session, null, null, null);
    }

    public Map<String, Object> start(TicketService.Resolved resolved, String intent, String rawDirection,
            String choice, HttpSession session,
            Double lat, Double lon, Double accuracy) {
        synchronized (session) { session.removeAttribute(PENDING); }
        Ticket ticket = resolved.ticket;
        boolean claimed = ticket.claimed();
        Pending pending;
        String options;

        try {
        if (!claimed) {
            // Holding the link is the claim; the first passkey registered on it takes it.
            if (ticket.claimExpiresAt != null
                    && ticket.claimExpiresAt.toInstant().isBefore(java.time.Instant.now())) {
                throw new ResponseStatusException(HttpStatus.GONE,
                    "등록 기한이 지났어요. 새 링크를 요청해 주세요.");
            }
            if ("existing".equals(choice)) {
                AssertionRequest request = claim.startExisting(TIMEOUT_MS);
                pending = new Pending(ticket, "CLAIM", null, ticket.issuedToEmail, null, request);
                options = request.toCredentialsGetJson();
            } else {
                // Layer 2 of the mobile-only policy: no USB or cross-platform authenticators.
                boolean platformOnly = !"OFF".equalsIgnoreCase(properties.getMobileOnly());
                PasskeyClaim.NewPasskey request = claim.startNew(ticket.issuedToEmail, ticket.phone, platformOnly, TIMEOUT_MS);
                pending = new Pending(ticket, "CLAIM", null, ticket.issuedToEmail, request, null);
                options = request.options().toCredentialsCreateJson();
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
            AssertionRequest request = claim.startHolder(ticket.holderId, TIMEOUT_MS);
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
        RegistrationResult result = claim.verify(pending.registration, credential);
        String aaguid = result.getAaguid().getHex().toLowerCase(Locale.ROOT);
        requireTicketAuthenticator(aaguid, false);
        // Inside the finish transaction: a claim that then loses to another phone rolls the
        // person and the passkey back with it.
        pending.holderId = claim.create(pending.registration, result);
        return attach(ticket, pending, resolved);
    }

    /**
     * The ticket's authenticator policy: only the allowed authenticator models, when a list
     * is set, and no security key or other device while the mobile-only policy is on.
     */
    void requireTicketAuthenticator(String aaguid, boolean crossPlatform) {
        List<String> allowed = properties.getAllowedAaguids();
        if (!allowed.isEmpty() && (aaguid == null || allowed.stream().noneMatch(model ->
                model.replace("-", "").equalsIgnoreCase(aaguid.replace("-", ""))))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "휴대폰의 지문·얼굴 인증으로 등록해 주세요. 데스크톱 인증기는 사용할 수 없어요.");
        }
        if (crossPlatform && !"OFF".equalsIgnoreCase(properties.getMobileOnly())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "입장권은 휴대폰에 저장된 패스키로만 받을 수 있어요. 휴대폰에서 다시 시도해 주세요.");
        }
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
        HolderRepository.Credential used = claim.answer(pending.assertion, credential, "등록한 패스키로 다시 시도해 주세요.");
        if ("CLAIM".equals(pending.intent)) {
            // A claim answered with a passkey the visitor already had joins their others -
            // as long as a ticket would have let that passkey be made. One made for a link
            // asked nothing of the authenticator, so a security key could otherwise carry
            // a ticket past the mobile-only policy.
            requireTicketAuthenticator(used.aaguid, PasskeyClaim.crossPlatform(credential));
            pending.holderId = used.holderId;
            return attach(ticket, pending, resolved);
        }
        if (pending.holderId == null || used.holderId != pending.holderId) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "이 입장권에 등록된 패스키가 아니에요.");
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
