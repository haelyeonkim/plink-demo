package com.plink.ticket.service;

import com.plink.ticket.model.EventSession;
import com.plink.ticket.model.Gate;
import com.plink.ticket.model.Grant;
import com.plink.ticket.model.Presence;
import com.plink.ticket.model.Ticket;
import com.plink.ticket.repository.AdmissionRepository;
import com.plink.ticket.repository.EventSessionRepository;
import com.plink.ticket.repository.GateRepository;
import com.plink.ticket.repository.PresentationRepository;
import com.plink.ticket.repository.TicketRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns a scanned code into a movement in the admission ledger.
 *
 * <p>Re-entry replaces "one ticket, one admission" with a presence invariant: IN only
 * succeeds from OUTSIDE and OUT only from INSIDE, and every transition is serialised by
 * a row lock. That is what keeps a forwarded QR useless — the ticket is already INSIDE,
 * so a second entry cannot happen while the holder is in the venue.
 */
@Service
public class AdmissionService {
    private final TicketRepository tickets;
    private final EventSessionRepository sessions;
    private final AdmissionRepository admissions;
    private final PresentationRepository grants;
    private final GateRepository gates;
    private final PresentationService presentations;
    private final TicketService ticketService;

    public AdmissionService(TicketRepository tickets, EventSessionRepository sessions,
            AdmissionRepository admissions, PresentationRepository grants, GateRepository gates,
            PresentationService presentations, TicketService ticketService) {
        this.tickets = tickets;
        this.sessions = sessions;
        this.admissions = admissions;
        this.grants = grants;
        this.gates = gates;
        this.presentations = presentations;
        this.ticketService = ticketService;
    }

    @Transactional
    public Map<String, Object> admit(Gate gate, String code, String method) {
        PresentationService.Verified verified = presentations.verify(code);
        Grant grant = verified.grant;

        Ticket ticket = tickets.lockById(grant.ticketId)
            .orElseThrow(() -> deny("입장권을 찾을 수 없어요."));
        if (!ticket.bound()) throw deny("등록이 완료되지 않은 입장권이에요.");
        if (ticket.revoked()) throw deny("사용할 수 없는 입장권이에요.");
        if (ticket.sessionId != gate.sessionId) throw deny("다른 회차의 입장권이에요.");

        EventSession session = sessions.findById(ticket.sessionId)
            .orElseThrow(() -> deny("회차 정보를 찾을 수 없어요."));
        Presence presence = presence(ticket);
        String direction = resolveDirection(gate, grant, presence);
        Instant now = Instant.now();

        // An accidental second scan must not toggle the state.
        if (presence.lastEventAt != null && gate.id.equals(presence.lastGateId)
                && presence.lastEventAt.toInstant().isAfter(now.minusSeconds(session.reentryCooldownSeconds))) {
            presentations.consume(grant, verified.counter);
            admissions.append(ticket.id, session.id, direction, gate.id, method, "DUPLICATE",
                "쿨다운 내 재스캔", grant.id);
            return result("DUPLICATE", direction, ticket, session, presence, "방금 처리된 입장권이에요.");
        }

        String note = "IN".equals(direction)
            ? enter(ticket, session, presence, gate, method, now)
            : exit(ticket, session, presence, gate);

        presentations.consume(grant, verified.counter);
        gates.touch(gate.id);
        admissions.append(ticket.id, session.id, direction, gate.id, method,
            "IN".equals(direction) ? "ADMITTED" : "EXITED", note, grant.id);

        Presence updated = admissions.find(ticket.id).orElse(presence);
        return result("IN".equals(direction) ? "ADMITTED" : "EXITED", direction, ticket, session, updated, null);
    }

    private String enter(Ticket ticket, EventSession session, Presence presence, Gate gate,
            String method, Instant now) {
        String note = null;
        if (presence.inside()) {
            // A ticket that is already INSIDE is the anti-sharing invariant doing its job:
            // a forwarded code cannot walk a second person in. Forgiving an unmatched exit
            // must therefore be time-gated, never immediate - otherwise the lenient policy
            // would hand the attack a way through. Only after the holder has plausibly been
            // gone for hours is an implicit exit the honest reading.
            String policy = session.unmatchedExit == null ? "LENIENT" : session.unmatchedExit;
            boolean forgiveDue = presence.insideSince != null && presence.insideSince.toInstant()
                .isBefore(now.minus(session.autoExitAfterMinutes, ChronoUnit.MINUTES));
            if ("STRICT".equals(policy) || !forgiveDue) {
                throw deny("이미 장내에 있는 입장권이에요. 퇴장을 먼저 처리하거나 안내 데스크에서 확인해 주세요.");
            }
            admissions.markOutside(ticket.id, gate.id);
            admissions.append(ticket.id, session.id, "OUT", gate.id, method, "EXITED",
                "짝 없는 퇴장 보정 (" + policy + ")", null);
            presence = admissions.find(ticket.id).orElse(presence);
            note = "짝 없는 퇴장 보정 후 입장";
        }
        if (session.gateOpensAt != null && now.isBefore(session.gateOpensAt.toInstant())) {
            throw deny("입장 시작 시간 전이에요.");
        }
        if (presence.entryCount > 0) {
            if (!session.reentryAllowed()) throw deny("재입장이 허용되지 않는 회차예요.");
            if (session.reentryLimited() && presence.reentryCount >= session.reentryMax) {
                throw deny("재입장 횟수를 모두 사용했어요.");
            }
            if (presence.lastExitAt != null && now.isAfter(
                    presence.lastExitAt.toInstant().plus(session.reentryGraceMinutes, ChronoUnit.MINUTES))) {
                throw deny("재입장 유효 시간이 지났어요.");
            }
        }
        int reentries = presence.reentryCount + (presence.entryCount > 0 ? 1 : 0);
        admissions.markInside(ticket.id, presence.entryCount + 1, reentries, gate.id);
        return note;
    }

    private String exit(Ticket ticket, EventSession session, Presence presence, Gate gate) {
        if (!presence.inside()) throw deny("장내 입장 기록이 없어요.");
        admissions.markOutside(ticket.id, gate.id);
        return null;
    }

    /**
     * Direction is decided by the terminal, never claimed by the QR. A bidirectional
     * terminal infers it from the ticket's presence state.
     */
    private String resolveDirection(Gate gate, Grant grant, Presence presence) {
        if (!gate.bidirectional()) {
            if (!grant.direction.equals(gate.direction)) {
                throw deny("IN".equals(gate.direction)
                    ? "입장 게이트예요. 입장하기로 다시 시도해 주세요."
                    : "퇴장 게이트예요. 퇴장하기로 다시 시도해 주세요.");
            }
            return gate.direction;
        }
        String inferred = presence.inside() ? "OUT" : "IN";
        if (!grant.direction.equals(inferred)) {
            throw deny(presence.inside()
                ? "이미 장내에 있어요. 퇴장하기로 다시 시도해 주세요."
                : "장내 기록이 없어요. 입장하기로 다시 시도해 주세요.");
        }
        return inferred;
    }

    private Presence presence(Ticket ticket) {
        return admissions.lock(ticket.id).orElseGet(() -> {
            admissions.create(ticket.id, ticket.sessionId);
            return admissions.lock(ticket.id).orElseThrow(() -> deny("입장 상태를 확인할 수 없어요."));
        });
    }

    /** Denials are evidence too, so they are appended in their own transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDenied(Gate gate, String code, String method, String reason) {
        Long ticketId = null;
        Long sessionId = null;
        String grantId = null;
        String[] parts = code == null ? new String[0] : code.trim().split("\\.");
        if (parts.length == 6) {
            grantId = parts[1].length() <= 32 ? parts[1] : null;
            if (grantId != null) {
                Grant grant = grants.findById(grantId).orElse(null);
                if (grant != null) {
                    ticketId = grant.ticketId;
                    sessionId = tickets.findById(grant.ticketId).map(t -> t.sessionId).orElse(null);
                }
            }
        }
        admissions.append(ticketId, sessionId == null ? gate.sessionId : sessionId, null, gate.id,
            method, "DENIED", trim(reason), grantId);
    }

    private Map<String, Object> result(String outcome, String direction, Ticket ticket, EventSession session,
            Presence presence, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("outcome", outcome);
        body.put("direction", direction);
        body.put("ticketRef", ticket.ticketRef);
        body.put("seat", ticket.seat);
        body.put("tier", ticket.tier);
        body.put("holderEmailMasked", TicketService.mask(ticket.holderEmail));
        body.put("inside", presence.inside());
        body.put("entryCount", presence.entryCount);
        body.put("reentryRemaining", ticketService.reentryRemaining(session, presence));
        if (message != null) body.put("message", message);
        return body;
    }

    private static String trim(String reason) {
        if (reason == null) return null;
        return reason.length() > 200 ? reason.substring(0, 200) : reason;
    }

    private ResponseStatusException deny(String message) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }
}
