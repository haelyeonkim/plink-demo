package com.plink.ticket.controller;

import com.plink.ticket.model.EventSession;
import com.plink.ticket.model.Presence;
import com.plink.ticket.model.Ticket;
import com.plink.ticket.repository.AdmissionRepository;
import com.plink.ticket.repository.EventSessionRepository;
import com.plink.ticket.repository.GateRepository;
import com.plink.ticket.repository.TicketRepository;
import com.plink.ticket.service.GateAuthService;
import com.plink.ticket.service.Secrets;
import com.plink.ticket.service.TicketService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Organiser console. Requires an authenticated administrator session. */
@RestController
@RequestMapping("/api/admin")
public class TicketAdminController {
    private final EventSessionRepository sessions;
    private final TicketRepository tickets;
    private final GateRepository gates;
    private final AdmissionRepository admissions;
    private final TicketService ticketService;
    private final GateAuthService gateAuth;

    public TicketAdminController(EventSessionRepository sessions, TicketRepository tickets, GateRepository gates,
            AdmissionRepository admissions, TicketService ticketService, GateAuthService gateAuth) {
        this.sessions = sessions;
        this.tickets = tickets;
        this.gates = gates;
        this.admissions = admissions;
        this.ticketService = ticketService;
        this.gateAuth = gateAuth;
    }

    @GetMapping("/sessions")
    public List<Map<String, Object>> listSessions() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (EventSession session : sessions.findAll()) result.add(describe(session));
        return result;
    }

    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createSession(@RequestBody Map<String, String> body) {
        String name = required(body.get("name"), "회차 이름을 입력해 주세요.");
        Timestamp startsAt = timestamp(body.get("startsAt"), "시작 시각을 ISO 형식으로 입력해 주세요.");
        Timestamp gateOpensAt = body.get("gateOpensAt") == null ? null
            : timestamp(body.get("gateOpensAt"), "개찰 시각을 ISO 형식으로 입력해 주세요.");
        long id = sessions.insert(name, body.get("venue"), startsAt, gateOpensAt);
        return describe(ticketService.requireSession(id));
    }

    /** Re-entry and exit rules are session policy, editable while the event runs. */
    @PutMapping("/sessions/{id}/policy")
    public Map<String, Object> updatePolicy(@PathVariable long id, @RequestBody Map<String, Object> body) {
        EventSession current = ticketService.requireSession(id);
        String mode = text(body.get("reentryMode"), current.reentryMode).toUpperCase(java.util.Locale.ROOT);
        if (!List.of("DISABLED", "LIMITED", "UNLIMITED").contains(mode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "재입장 모드는 DISABLED, LIMITED, UNLIMITED 중 하나여야 해요.");
        }
        String unmatched = text(body.get("unmatchedExit"), current.unmatchedExit).toUpperCase(java.util.Locale.ROOT);
        if (!List.of("STRICT", "LENIENT", "AUTO_EXIT").contains(unmatched)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "퇴장 미스캔 정책은 STRICT, LENIENT, AUTO_EXIT 중 하나여야 해요.");
        }
        if (body.containsKey("transferMax") || body.containsKey("transferClosesMinutesBefore")
                || body.containsKey("transferAfterFirstEntry")) {
            sessions.updateTransferPolicy(id,
                number(body.get("transferMax"), current.transferMax),
                number(body.get("transferClosesMinutesBefore"), current.transferClosesMinutesBefore),
                body.get("transferAfterFirstEntry") == null ? current.transferAfterFirstEntry
                    : Boolean.parseBoolean(body.get("transferAfterFirstEntry").toString()));
        }
        if (body.containsKey("faceRequired") || body.containsKey("reentryRequiresFace")
                || body.containsKey("faceLiveness") || body.containsKey("faceChallengeOn")
                || body.containsKey("faceRetentionDays")) {
            sessions.updateFacePolicy(id,
                body.get("faceRequired") == null ? current.faceRequired
                    : Boolean.parseBoolean(body.get("faceRequired").toString()),
                body.get("reentryRequiresFace") == null ? current.reentryRequiresFace
                    : Boolean.parseBoolean(body.get("reentryRequiresFace").toString()),
                text(body.get("faceLiveness"), current.faceLiveness),
                text(body.get("faceChallengeOn"), current.faceChallengeOn),
                number(body.get("faceRetentionDays"), current.faceRetentionDays));
        }
        sessions.updatePolicy(id, mode,
            number(body.get("reentryMax"), current.reentryMax),
            number(body.get("reentryGraceMinutes"), current.reentryGraceMinutes),
            number(body.get("reentryCooldownSeconds"), current.reentryCooldownSeconds),
            body.get("exitScanRequired") == null ? current.exitScanRequired
                : Boolean.parseBoolean(body.get("exitScanRequired").toString()),
            unmatched);
        return describe(ticketService.requireSession(id));
    }

    /** Issues a ticket and mails its personal URL. The token is never returned to the console. */
    @PostMapping("/sessions/{id}/tickets")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> issueTicket(@PathVariable long id, @RequestBody Map<String, String> body) {
        Map<String, Object> issued = ticketService.issue(id, body.get("email"), body.get("seat"), body.get("tier"));
        issued.remove("url");
        return issued;
    }

    @GetMapping("/sessions/{id}/tickets")
    public List<Map<String, Object>> listTickets(@PathVariable long id) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Ticket ticket : tickets.findBySession(id)) {
            Presence presence = admissions.find(ticket.id).orElse(null);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ticketId", ticket.id);
            row.put("ticketRef", ticket.ticketRef);
            row.put("seat", ticket.seat);
            row.put("tier", ticket.tier);
            row.put("status", ticket.status);
            row.put("issuedToEmail", TicketService.mask(ticket.issuedToEmail));
            row.put("holderEmail", TicketService.mask(ticket.holderEmail));
            row.put("reissueCount", ticket.reissueCount);
            row.put("transferCount", ticket.transferCount);
            row.put("inside", presence != null && presence.inside());
            row.put("entryCount", presence == null ? 0 : presence.entryCount);
            row.put("reentryCount", presence == null ? 0 : presence.reentryCount);
            result.add(row);
        }
        return result;
    }

    /**
     * Registers a gate terminal. The token is shown once here and stored only as a
     * keyed hash, so it cannot be recovered later — re-register to rotate it.
     */
    @PostMapping("/sessions/{id}/gates")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createGate(@PathVariable long id, @RequestBody Map<String, String> body) {
        ticketService.requireSession(id);
        String gateId = required(body.get("gateId"), "게이트 ID를 입력해 주세요.");
        if (!gateId.matches("[A-Za-z0-9_-]{1,32}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "게이트 ID는 영문·숫자 32자 이내여야 해요.");
        }
        if (gates.findById(gateId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 등록된 게이트 ID예요.");
        }
        String direction = GateAuthService.direction(body.get("direction"));
        String token = Secrets.randomToken(24);
        gates.insert(gateId, id, body.get("label"), body.get("zone"), direction, gateAuth.hash(token));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("gateId", gateId);
        result.put("direction", direction);
        result.put("gateToken", token);
        result.put("note", "이 토큰은 다시 확인할 수 없어요. 단말에 바로 등록해 주세요.");
        return result;
    }

    @GetMapping("/sessions/{id}/gates")
    public List<Map<String, Object>> listGates(@PathVariable long id) {
        List<Map<String, Object>> result = new ArrayList<>();
        gates.findBySession(id).forEach(gate -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("gateId", gate.id);
            row.put("label", gate.label);
            row.put("zone", gate.zone);
            row.put("direction", gate.direction);
            result.add(row);
        });
        return result;
    }

    /** Live occupancy plus the two reports operations actually watch. */
    @GetMapping("/sessions/{id}/occupancy")
    public Map<String, Object> occupancy(@PathVariable long id) {
        ticketService.requireSession(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.putAll(admissions.occupancy(id));
        result.put("stillInside", admissions.stillInside(id));
        result.put("recent", admissions.recentEvents(id, 30));
        return result;
    }

    private Map<String, Object> describe(EventSession session) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", session.id);
        row.put("name", session.name);
        row.put("venue", session.venue);
        row.put("startsAt", session.startsAt.toInstant().toString());
        row.put("gateOpensAt", session.gateOpensAt == null ? null : session.gateOpensAt.toInstant().toString());
        row.put("reentryMode", session.reentryMode);
        row.put("reentryMax", session.reentryMax);
        row.put("reentryGraceMinutes", session.reentryGraceMinutes);
        row.put("reentryCooldownSeconds", session.reentryCooldownSeconds);
        row.put("exitScanRequired", session.exitScanRequired);
        row.put("unmatchedExit", session.unmatchedExit);
        row.put("autoExitAfterMinutes", session.autoExitAfterMinutes);
        row.put("transferMax", session.transferMax);
        row.put("transferClosesMinutesBefore", session.transferClosesMinutesBefore);
        row.put("transferAfterFirstEntry", session.transferAfterFirstEntry);
        row.put("faceRequired", session.faceRequired);
        row.put("reentryRequiresFace", session.reentryRequiresFace);
        row.put("faceLiveness", session.faceLiveness);
        row.put("faceChallengeOn", session.faceChallengeOn);
        row.put("faceRetentionDays", session.faceRetentionDays);
        return row;
    }

    private static String required(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return value.trim();
    }

    private static String text(Object value, String fallback) {
        return value == null ? fallback : value.toString();
    }

    private static int number(Object value, int fallback) {
        if (value == null) return fallback;
        try { return Integer.parseInt(value.toString()); }
        catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "숫자 형식을 확인해 주세요.");
        }
    }

    private static Timestamp timestamp(String value, String message) {
        try { return Timestamp.from(Instant.parse(required(value, message))); }
        catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
    }
}
