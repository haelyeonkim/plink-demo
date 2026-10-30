package com.plink.ticket.service;

import com.plink.ticket.model.Gate;
import com.plink.ticket.model.Ticket;
import com.plink.ticket.repository.AdmissionRepository;
import com.plink.ticket.repository.TicketRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Replays movements a terminal recorded while it could not reach the server.
 *
 * <p>Presence is global state, so an offline terminal cannot decide whether an entry is
 * legitimate - it lets people through and queues what it saw. Replay is where the truth
 * is worked out: events are applied in capture order, and anything that contradicts the
 * ledger is recorded as a flagged refusal rather than being forced into the state. That
 * is the honest trade an offline mode makes, and the flags are what operations chase.
 *
 * <p>Each event is applied in its own transaction, under the same locks a live scan
 * takes. One that is refused rolls back on its own - its consumed grant and half a
 * movement with it - and leaves the ones before and after it standing.
 */
@Service
public class OfflineSyncService {
    private final PresentationService presentations;
    private final AdmissionService admissions;
    private final TicketRepository tickets;
    private final AdmissionRepository ledger;
    private final TransactionTemplate perEvent;

    public OfflineSyncService(PresentationService presentations, AdmissionService admissions,
            TicketRepository tickets, AdmissionRepository ledger, PlatformTransactionManager transactionManager) {
        this.presentations = presentations;
        this.admissions = admissions;
        this.tickets = tickets;
        this.ledger = ledger;
        this.perEvent = new TransactionTemplate(transactionManager);
        this.perEvent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Map<String, Object> replay(Gate gate, List<Map<String, Object>> events) {
        List<Map<String, Object>> ordered = new ArrayList<>(events);
        ordered.sort(Comparator.comparing(event -> String.valueOf(event.getOrDefault("capturedAt", ""))));

        int applied = 0, flagged = 0;
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (Map<String, Object> event : ordered) {
            String code = event.get("code") == null ? null : String.valueOf(event.get("code"));
            // A queued code is a QR the terminal read; the terminal does not get to say
            // otherwise. Faces are matched by the server, online, or not at all.
            String method = "QR";
            String capturedAt = String.valueOf(event.getOrDefault("capturedAt", ""));
            try {
                perEvent.executeWithoutResult(status -> {
                    PresentationService.Verified verified = presentations.verify(code, true);
                    if (verified.grant.forCoupon()) {
                        throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,
                            "쿠폰 QR은 입장 기록이 될 수 없어요.");
                    }
                    Ticket ticket = tickets.lockById(verified.grant.ticketId)
                        .orElseThrow(() -> new IllegalStateException("ticket missing"));
                    admissions.move(gate, ticket, verified.grant.direction, method, verified.grant.id,
                        () -> presentations.consume(verified.grant, verified.counter));
                });
                applied++;
            } catch (RuntimeException refused) {
                String reason = refused instanceof ResponseStatusException status
                    ? status.getReason() : "오프라인 기록을 확인할 수 없어요.";
                perEvent.executeWithoutResult(status -> ledger.appendDetailed(null, gate.sessionId, null,
                    gate.id, method, "DENIED", "오프라인 동기화 충돌: " + reason, null, null, true, true));
                conflicts.add(Map.of("capturedAt", capturedAt, "reason", String.valueOf(reason)));
                flagged++;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("received", events.size());
        result.put("applied", applied);
        result.put("flagged", flagged);
        result.put("conflicts", conflicts);
        return result;
    }
}
