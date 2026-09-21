package com.plink.ticket.controller;

import com.plink.ticket.model.EventSession;
import com.plink.ticket.model.Gate;
import com.plink.ticket.model.Presence;
import com.plink.ticket.model.Ticket;
import com.plink.ticket.model.TicketField;
import com.plink.ticket.repository.AdmissionRepository;
import com.plink.ticket.repository.EventSessionRepository;
import com.plink.ticket.repository.GateRepository;
import com.plink.ticket.repository.TicketFieldRepository;
import com.plink.ticket.repository.TicketRepository;
import com.plink.ticket.repository.ZoneCapacityRepository;
import com.plink.ticket.live.LiveEvents;
import com.plink.ticket.live.LiveSnapshots;
import com.plink.ticket.service.GateAuthService;
import com.plink.ticket.service.GateSetupService;
import com.plink.ticket.service.Secrets;
import com.plink.ticket.service.TextCipher;
import com.plink.ticket.service.AdmissionService;
import com.plink.ticket.service.TicketService;
import com.plink.account.CurrentUser;
import org.springframework.security.core.Authentication;
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
    private final AdmissionService admissionService;
    private final TextCipher cipher;
    private final GateSetupService gateSetup;
    private final ZoneCapacityRepository zoneCapacities;
    private final TicketFieldRepository fields;
    private final LiveEvents live;
    private final LiveSnapshots snapshots;

    public TicketAdminController(EventSessionRepository sessions, TicketRepository tickets, GateRepository gates,
            AdmissionRepository admissions, TicketService ticketService, GateAuthService gateAuth,
            AdmissionService admissionService, TextCipher cipher, GateSetupService gateSetup,
            ZoneCapacityRepository zoneCapacities, TicketFieldRepository fields,
            LiveEvents live, LiveSnapshots snapshots) {
        this.sessions = sessions;
        this.tickets = tickets;
        this.gates = gates;
        this.admissions = admissions;
        this.ticketService = ticketService;
        this.gateAuth = gateAuth;
        this.admissionService = admissionService;
        this.cipher = cipher;
        this.gateSetup = gateSetup;
        this.zoneCapacities = zoneCapacities;
        this.fields = fields;
        this.live = live;
        this.snapshots = snapshots;
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
        String name = required(body.get("name"), "행사 이름을 입력해 주세요.");
        Timestamp startsAt = timestamp(body.get("startsAt"), "시작 시각을 ISO 형식으로 입력해 주세요.");
        Timestamp gateOpensAt = body.get("gateOpensAt") == null ? null
            : timestamp(body.get("gateOpensAt"), "개찰 시각을 ISO 형식으로 입력해 주세요.");
        long id = sessions.insert(name, body.get("venue"), startsAt, gateOpensAt);
        return describe(ticketService.requireSession(id));
    }

    /**
     * The event itself: what it is called, where it is, when it starts and when the
     * doors open. A date moves more often than anyone plans for, and every issued
     * ticket keeps working across the change.
     */
    @PutMapping("/sessions/{id}")
    public Map<String, Object> updateSession(@PathVariable long id, @RequestBody Map<String, String> body) {
        EventSession current = ticketService.requireSession(id);
        String name = required(body.get("name"), "행사 이름을 입력해 주세요.");
        Timestamp startsAt = body.get("startsAt") == null || body.get("startsAt").isBlank()
            ? current.startsAt
            : timestamp(body.get("startsAt"), "시작 시각을 ISO 형식으로 입력해 주세요.");
        Timestamp gateOpensAt = body.get("gateOpensAt") == null || body.get("gateOpensAt").isBlank()
            ? null
            : timestamp(body.get("gateOpensAt"), "입장 시각을 ISO 형식으로 입력해 주세요.");
        if (gateOpensAt != null && gateOpensAt.after(startsAt)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "입장 시각은 시작 시각보다 앞서야 해요.");
        }
        String venue = body.get("venue") == null || body.get("venue").isBlank()
            ? null : body.get("venue").trim();
        sessions.updateDetails(id, name, venue, startsAt, gateOpensAt);
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
        if (body.containsKey("seats")) writeThrough(id, "좌석", "SEAT", body.get("seats"));
        if (body.containsKey("tiers")) writeThrough(id, "등급", "TIER", body.get("tiers"));
        if (body.containsKey("crowdBusyPercent") || body.containsKey("crowdSteadyPercent")) {
            int busy = number(body.get("crowdBusyPercent"), current.crowdBusyPercent);
            int steady = number(body.get("crowdSteadyPercent"), current.crowdSteadyPercent);
            if (busy < 1 || busy > 100 || steady < 1 || steady > 100) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "혼잡도 기준은 1에서 100 사이의 백분율이어야 해요.");
            }
            if (steady >= busy) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "'보통' 기준은 '혼잡' 기준보다 낮아야 해요.");
            }
            sessions.updateCrowdLevels(id, busy, steady);
            publishCrowding(id);
        }
        if (body.containsKey("claimRequiresOtp")) {
            sessions.updateClaimPolicy(id, Boolean.parseBoolean(body.get("claimRequiresOtp").toString()));
        }
        if (body.containsKey("geoMode") || body.containsKey("venueLat") || body.containsKey("venueLon")
                || body.containsKey("geoRadiusMeters")) {
            String geoMode = text(body.get("geoMode"), current.geoMode).toUpperCase(java.util.Locale.ROOT);
            if (!List.of("OFF", "ADVISE", "ENFORCE").contains(geoMode)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "위치 정책은 OFF, ADVISE, ENFORCE 중 하나여야 해요.");
            }
            sessions.updateGeoPolicy(id,
                decimal(body.get("venueLat"), current.venueLat),
                decimal(body.get("venueLon"), current.venueLon),
                number(body.get("geoRadiusMeters"), current.geoRadiusMeters), geoMode);
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

    /**
     * Issues a ticket, mails the personal URL and, when a phone number is given, sends it
     * by SMS as well. The URL comes back once here: only its keyed hash is stored, so
     * this is the only moment it can be shown without minting a new one.
     */
    @PostMapping("/sessions/{id}/tickets")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> issueTicket(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return ticketService.issue(id, text(body.get("email"), null), blankToNull(body.get("seat")),
            blankToNull(body.get("tier")), blankToNull(body.get("phone")), true, fieldValues(id, body));
    }

    /**
     * What the organiser's own fields say for this ticket. A row may name them in a
     * nested "values" object or flat alongside the address, because a spreadsheet column
     * arrives flat and a form posts an object.
     */
    private Map<String, String> fieldValues(long sessionId, Map<String, Object> body) {
        Map<String, String> nested = body.get("values") instanceof Map<?, ?> map ? castRow(map).entrySet()
            .stream().collect(LinkedHashMap::new, (into, e) -> into.put(String.valueOf(e.getKey()),
                text(e.getValue(), null)), LinkedHashMap::putAll) : new LinkedHashMap<>();
        Map<String, String> values = new LinkedHashMap<>();
        for (TicketField field : fields.findBySession(sessionId)) {
            String value = nested.containsKey(field.label) ? nested.get(field.label)
                : blankToNull(body.get(field.label));
            if (value != null && !value.isBlank()) values.put(field.label, value.trim());
        }
        return values;
    }

    /**
     * Issues many tickets in one request, as a spreadsheet export arrives.
     *
     * <p>Each row stands alone: one bad address or a seat already taken is reported
     * against that row and the rest still go out. Failing the whole upload for one typo
     * would mean hand-editing a file of hundreds and starting again.
     */
    @PostMapping("/sessions/{id}/tickets/bulk")
    public Map<String, Object> issueTickets(@PathVariable long id, @RequestBody Map<String, Object> body) {
        ticketService.requireSession(id);
        Object rows = body.get("rows");
        if (!(rows instanceof List<?> list) || list.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "발급할 행이 없어요.");
        }
        if (list.size() > MAX_BULK_ROWS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "한 번에 " + MAX_BULK_ROWS + "행까지 올릴 수 있어요.");
        }
        boolean notify = !"false".equalsIgnoreCase(String.valueOf(body.get("notify")));
        List<Map<String, Object>> results = new ArrayList<>();
        int issued = 0;
        for (int index = 0; index < list.size(); index++) {
            Map<String, Object> row = list.get(index) instanceof Map<?, ?> map
                ? castRow(map) : Map.of();
            Map<String, Object> outcome = new LinkedHashMap<>();
            outcome.put("row", index + 1);
            outcome.put("email", text(row.get("email"), ""));
            try {
                Map<String, Object> ticket = ticketService.issue(id, text(row.get("email"), null),
                    blankToNull(row.get("seat")), blankToNull(row.get("tier")),
                    blankToNull(row.get("phone")), notify, fieldValues(id, row));
                outcome.put("ok", true);
                outcome.put("ticketRef", ticket.get("ticketRef"));
                outcome.put("deliveredVia", ticket.get("deliveredVia"));
                issued++;
            } catch (ResponseStatusException refused) {
                outcome.put("ok", false);
                outcome.put("error", refused.getReason());
            } catch (RuntimeException failed) {
                outcome.put("ok", false);
                outcome.put("error", "발급하지 못했어요.");
            }
            results.add(outcome);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("issued", issued);
        summary.put("failed", results.size() - issued);
        summary.put("results", results);
        return summary;
    }

    private static final int MAX_BULK_ROWS = 500;

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castRow(Map<?, ?> row) { return (Map<String, Object>) row; }

    private static String blankToNull(Object value) {
        String text = value == null ? "" : value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    /** Switches a ticket off so it stops opening and stops admitting, or back on. */
    @PostMapping("/tickets/{ticketId}/status")
    public Map<String, Object> setTicketStatus(@PathVariable long ticketId,
            @RequestBody Map<String, Object> body) {
        return ticketService.setRevoked(ticketId, Boolean.TRUE.equals(body.get("revoked")));
    }

    /**
     * Deletes a ticket issued by mistake. {@code force} is the operator saying they mean
     * to take its gate record with it.
     */
    @DeleteMapping("/tickets/{ticketId}")
    public Map<String, Object> deleteTicket(@PathVariable long ticketId,
            @RequestParam(defaultValue = "false") boolean force) {
        return ticketService.delete(ticketId, force);
    }

    /**
     * Shows a ticket's link again. Because no live token is kept, this rotates: the new
     * link is returned once and the previous one stops resolving immediately.
     */
    @PostMapping("/tickets/{ticketId}/link")
    public Map<String, Object> reissueLink(@PathVariable long ticketId,
            @RequestBody(required = false) Map<String, Object> body) {
        boolean notify = body != null && Boolean.TRUE.equals(body.get("notify"));
        return ticketService.reissueForConsole(ticketId, notify);
    }

    /**
     * The link as issued, for the operator who is asked "what was my link again?".
     *
     * <p>Reading it changes nothing: the ticket keeps working, the holder's registration
     * stands, and no new link is minted. Tickets issued before the link was kept have
     * only the hash, and say so rather than quietly rotating.
     */
    @GetMapping("/tickets/{ticketId}/link")
    public Map<String, Object> ticketLink(@PathVariable long ticketId) {
        Ticket ticket = tickets.findById(ticketId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "입장권을 찾을 수 없어요."));
        String token = cipher.open(ticket.tokenCipher);
        if (token == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "이 입장권은 링크를 보관하기 전에 발급되어 다시 볼 수 없어요. "
                + "재발급하면 그때부터는 언제든 볼 수 있습니다.");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ticketId", ticket.id);
        result.put("ticketRef", ticket.ticketRef);
        result.put("url", ticketService.urlFor(ticket.sessionId, token));
        result.put("deliveredVia", ticket.deliveredVia);
        return result;
    }

    /**
     * The fields a ticket carries in this event: seat, tier, or anything the organiser
     * names. Adding one makes it appear on the issue form and in the ticket list.
     */
    @PostMapping("/sessions/{id}/fields")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> addField(@PathVariable long id, @RequestBody Map<String, Object> body) {
        ticketService.requireSession(id);
        String label = required(text(body.get("label"), null), "항목 이름을 입력해 주세요.");
        if (label.length() > 40) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "항목 이름은 40자까지예요.");
        }
        String kind = text(body.get("kind"), "CUSTOM").toUpperCase(java.util.Locale.ROOT);
        if (!List.of("SEAT", "TIER", "CUSTOM").contains(kind)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "항목 유형을 확인해 주세요.");
        }
        List<TicketField> existing = fields.findBySession(id);
        for (TicketField field : existing) {
            if (field.label.equalsIgnoreCase(label)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "같은 이름의 항목이 이미 있어요.");
            }
            // One seat column, so one seat field. The same goes for the tier.
            if (!"CUSTOM".equals(kind) && kind.equals(field.kind)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "'" + field.label + "' 항목이 이미 그 역할을 맡고 있어요.");
            }
        }
        if (existing.size() >= 20) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "항목은 20개까지 만들 수 있어요.");
        }
        long fieldId = fields.insert(id, label, kind, catalogue(body.get("values"), null));
        return describeField(fields.findById(fieldId).orElseThrow());
    }

    @PutMapping("/fields/{fieldId}")
    public Map<String, Object> updateField(@PathVariable long fieldId, @RequestBody Map<String, Object> body) {
        TicketField field = fields.findById(fieldId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "항목을 찾을 수 없어요."));
        if (body.containsKey("label")) {
            String label = required(text(body.get("label"), null), "항목 이름을 입력해 주세요.");
            for (TicketField other : fields.findBySession(field.sessionId)) {
                if (other.id != field.id && other.label.equalsIgnoreCase(label)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "같은 이름의 항목이 이미 있어요.");
                }
            }
            fields.rename(field.id, label);
        }
        if (body.containsKey("values")) {
            fields.updateValues(field.id, catalogue(body.get("values"), null));
        }
        return describeField(fields.findById(fieldId).orElseThrow());
    }

    /**
     * Removes the field. Tickets keep whatever they were issued with - the record of
     * what went out does not change because the form did.
     */
    @DeleteMapping("/fields/{fieldId}")
    public Map<String, Object> deleteField(@PathVariable long fieldId) {
        TicketField field = fields.findById(fieldId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "항목을 찾을 수 없어요."));
        fields.delete(field.id);
        return Map.of("deleted", true, "label", field.label);
    }

    /** Keeps the older seats/tiers keys working by writing them into their field. */
    private void writeThrough(long sessionId, String label, String kind, Object values) {
        String stored = catalogue(values, null);
        TicketField existing = fields.findBySession(sessionId).stream()
            .filter(field -> kind.equals(field.kind)).findFirst().orElse(null);
        if (existing == null) {
            if (stored != null) fields.insert(sessionId, label, kind, stored);
        } else {
            fields.updateValues(existing.id, stored);
        }
    }

    /** A ticket's own fields, as stored. Unreadable JSON is shown as nothing. */
    private static Map<String, Object> readAttributes(String stored) {
        if (stored == null || stored.isBlank()) return Map.of();
        try {
            return new tools.jackson.databind.ObjectMapper()
                .readValue(stored, new tools.jackson.core.type.TypeReference<Map<String, Object>>() { });
        } catch (RuntimeException unreadable) {
            return Map.of();
        }
    }

    private Map<String, Object> describeField(TicketField field) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", field.id);
        row.put("label", field.label);
        row.put("kind", field.kind);
        row.put("values", field.values());
        return row;
    }

    /**
     * How many people each place holds.
     *
     * <p>The body is a map of place to capacity; a null or zero drops the number and
     * returns that place to the relative reading. Places themselves come from the gates,
     * so this only fills in a figure for somewhere that already exists.
     */
    @PutMapping("/sessions/{id}/zones")
    public Map<String, Object> updateZoneCapacity(@PathVariable long id, @RequestBody Map<String, Object> body) {
        ticketService.requireSession(id);
        Object zones = body.get("zones");
        if (!(zones instanceof Map<?, ?> entries)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "장소별 수용 인원을 보내 주세요.");
        }
        for (Map.Entry<?, ?> entry : entries.entrySet()) {
            String zone = String.valueOf(entry.getKey()).trim();
            if (zone.isEmpty()) continue;
            int capacity = number(entry.getValue(), 0);
            if (capacity <= 0) { zoneCapacities.delete(id, zone); continue; }
            if (capacity > 1_000_000) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "수용 인원이 너무 큽니다.");
            }
            zoneCapacities.save(id, zone, capacity);
        }
        publishCrowding(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("zoneCapacity", zoneCapacities.findBySession(id));
        return result;
    }

    /** A changed number moves the bars on every phone already watching. */
    private void publishCrowding(long sessionId) {
        live.publish(sessionId, "CROWDING", snapshots.crowding(sessionId));
    }

    /**
     * Deletes a session and everything hanging off it. Refused once anyone has been
     * admitted unless the caller says so explicitly, because the ledger is the record of
     * who came in.
     */
    @DeleteMapping("/sessions/{id}")
    public Map<String, Object> deleteSession(@PathVariable long id,
            @RequestParam(defaultValue = "false") boolean force) {
        ticketService.requireSession(id);
        int issued = tickets.findBySession(id).size();
        int admitted = admissions.occupancy(id).get("everEntered") instanceof Number entered
            ? entered.intValue() : 0;
        if (!force && (issued > 0 || admitted > 0)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "발급된 입장권 " + issued + "장(입장 이력 " + admitted + "건)이 있어요. "
                + "정말 지우려면 force=true로 다시 요청해 주세요.");
        }
        sessions.delete(id);
        return Map.of("deleted", true, "tickets", issued, "admitted", admitted);
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
            row.put("attributes", readAttributes(ticket.attributes));
            row.put("status", ticket.status);
            row.put("issuedToEmail", TicketService.mask(ticket.issuedToEmail));
            row.put("holderEmail", TicketService.mask(ticket.holderEmail));
            row.put("reissueCount", ticket.reissueCount);
            row.put("transferCount", ticket.transferCount);
            row.put("phone", ticket.phone);
            row.put("deliveredVia", ticket.deliveredVia);
            row.put("inside", presence != null && presence.inside());
            row.put("entryCount", presence == null ? 0 : presence.entryCount);
            row.put("reentryCount", presence == null ? 0 : presence.reentryCount);
            // When the gate last saw this ticket, and which way it went - the two facts
            // the console is asked for when somebody says "did they come in yet?".
            row.put("lastEventAt", presence == null || presence.lastEventAt == null
                ? null : presence.lastEventAt.toInstant().toString());
            row.put("lastExitAt", presence == null || presence.lastExitAt == null
                ? null : presence.lastExitAt.toInstant().toString());
            row.put("insideSince", presence == null || presence.insideSince == null
                ? null : presence.insideSince.toInstant().toString());
            result.add(row);
        }
        return result;
    }

    /**
     * Registers a gate terminal. The token stays readable here, because it is typed into
     * staff tablets through the day and an unrecoverable one means re-registering
     * terminals mid-event. It is stored encrypted, never in the clear.
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
        // A terminal without a place cannot answer "how busy is the main hall?".
        String zone = required(body.get("zone"), "게이트가 있는 장소를 입력해 주세요.");
        gates.insert(gateId, id, body.get("label"), zone, direction,
            gateAuth.hash(token), cipher.seal(token), gateAuth.tokenExpiry());

        // The terminal is enrolled from a link and a code; nobody types the token.
        Map<String, Object> result = new LinkedHashMap<>(gateSetup.open(gateId));
        result.put("direction", direction);
        return result;
    }

    /** Issues a fresh setup link and code for a terminal. */
    @PostMapping("/gates/{gateId}/setup")
    public Map<String, Object> reopenGateSetup(@PathVariable String gateId) {
        return gateSetup.open(gateId);
    }

    /** Issues a new token for a terminal and frees whichever device held the gate. */
    @PostMapping("/gates/{gateId}/token")
    public Map<String, Object> rotateGateToken(@PathVariable String gateId) {
        gates.findById(gateId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "게이트를 찾을 수 없어요."));
        String token = Secrets.randomToken(24);
        gates.rotateToken(gateId, gateAuth.hash(token), cipher.seal(token), gateAuth.tokenExpiry());
        return Map.of("gateId", gateId, "gateToken", token);
    }

    /**
     * Pushes a terminal's end date out without touching its token.
     *
     * <p>The tablet on the stand keeps working through this: the operator is answering
     * "is this terminal still ours?", which is a question about time rather than about
     * credentials. Rotating the token is the answer to the other question.
     *
     * @param days how long to grant, or 0 for a terminal that should not expire at all -
     *     a fixture wired into a venue, or a gate being lived with while it is tested.
     *     Omitted means the configured window.
     */
    @PostMapping("/gates/{gateId}/renew")
    public Map<String, Object> renewGate(@PathVariable String gateId,
            @RequestParam(required = false) Integer days) {
        Gate gate = gates.findById(gateId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "게이트를 찾을 수 없어요."));
        if (days != null && days < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "유효기간은 0일 이상이어야 해요.");
        }
        java.sql.Timestamp until = days == null ? gateAuth.tokenExpiry()
            : days == 0 ? null
            : java.sql.Timestamp.from(java.time.Instant.now().plus(java.time.Duration.ofDays(days)));
        gates.renewToken(gate.id, until);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("gateId", gate.id);
        result.put("tokenExpiresAt", until == null ? null : until.toInstant().toString());
        return result;
    }

    /** Releases the terminal holding this gate, so another tablet can take it. */
    @PostMapping("/gates/{gateId}/release")
    public Map<String, Object> releaseGate(@PathVariable String gateId) {
        gates.findById(gateId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "게이트를 찾을 수 없어요."));
        gates.releaseDevice(gateId);
        return Map.of("released", true);
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
            String setupToken = cipher.open(gate.setupTokenCipher);
            row.put("setupUrl", setupToken == null ? null : gateSetup.setupUrl(setupToken));
            row.put("setupCode", cipher.open(gate.setupCodeCipher));
            row.put("setupExpiresAt", gate.setupExpiresAt == null ? null
                : gate.setupExpiresAt.toInstant().toString());
            row.put("boundDevice", gate.boundDevice == null ? null
                : gate.boundDevice.substring(0, Math.min(8, gate.boundDevice.length())));
            row.put("lastSeenAt", gate.lastSeenAt == null ? null : gate.lastSeenAt.toInstant().toString());
            row.put("tokenExpiresAt", gate.tokenExpiresAt == null ? null
                : gate.tokenExpiresAt.toInstant().toString());
            row.put("tokenExpired", gate.tokenExpired());
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
        result.put("byGate", admissions.byGate(id));
        result.put("byZone", admissions.byZone(id));
        return result;
    }

    /** Signals worth a human look; none of them is a verdict on its own. */
    @GetMapping("/sessions/{id}/anomalies")
    public Map<String, Object> anomalies(@PathVariable long id) {
        ticketService.requireSession(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("repeatedRefusals", admissions.repeatedRefusals(id, 3));
        result.put("impossibleMovements", admissions.impossibleMovements(id, 60));
        result.put("offlineConflicts", admissions.flaggedEvents(id));
        return result;
    }

    /**
     * Staff correction. The ledger is append-only, so a fix is a new entry carrying who
     * made it and why - never an edit of what the gate recorded.
     */
    @PostMapping("/tickets/{ticketId}/movement")
    public Map<String, Object> staffMovement(@PathVariable long ticketId,
            @RequestBody Map<String, String> body, Authentication operator) {
        Ticket ticket = tickets.findById(ticketId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "입장권을 찾을 수 없어요."));
        String direction = required(body.get("direction"), "방향을 지정해 주세요.").toUpperCase(java.util.Locale.ROOT);
        if (!List.of("IN", "OUT").contains(direction)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "방향은 IN 또는 OUT이어야 해요.");
        }
        String reason = required(body.get("reason"), "사유를 입력해 주세요.");
        String who = CurrentUser.of(operator).map(user -> user.email).orElse("unknown");

        Presence presence = admissions.find(ticketId).orElse(null);
        if (presence == null) {
            admissions.create(ticketId, ticket.sessionId);
            presence = admissions.find(ticketId).orElseThrow();
        }
        if ("IN".equals(direction)) {
            admissions.markInside(ticketId, presence.entryCount + 1,
                presence.reentryCount + (presence.entryCount > 0 ? 1 : 0), null);
        } else {
            admissions.markOutside(ticketId, null);
        }
        admissions.appendDetailed(ticketId, ticket.sessionId, direction, null, "STAFF",
            "IN".equals(direction) ? "ADMITTED" : "EXITED", reason, null, who, false, false);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("direction", direction);
        result.put("operator", who);
        result.put("inside", "IN".equals(direction));
        return result;
    }

    /** Accepts a list or a block of text and stores one entry per line. */
    private static String catalogue(Object value, String fallback) {
        if (value == null) return fallback;
        java.util.stream.Stream<String> entries = value instanceof List<?> list
            ? list.stream().map(String::valueOf)
            : java.util.Arrays.stream(value.toString().split("[\\r\\n,]"));
        List<String> cleaned = entries.map(String::trim).filter(entry -> !entry.isEmpty())
            .distinct().limit(2000).toList();
        return cleaned.isEmpty() ? null : String.join("\n", cleaned);
    }

    private static Double decimal(Object value, Double fallback) {
        if (value == null) return fallback;
        try { return Double.valueOf(value.toString()); }
        catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "좌표 형식을 확인해 주세요.");
        }
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
        row.put("claimRequiresOtp", session.claimRequiresOtp);
        row.put("faceRequired", session.faceRequired);
        row.put("reentryRequiresFace", session.reentryRequiresFace);
        row.put("faceLiveness", session.faceLiveness);
        row.put("faceChallengeOn", session.faceChallengeOn);
        row.put("faceRetentionDays", session.faceRetentionDays);
        row.put("geoMode", session.geoMode);
        row.put("venueLat", session.venueLat);
        row.put("venueLon", session.venueLon);
        row.put("geoRadiusMeters", session.geoRadiusMeters);
        List<TicketField> configured = fields.findBySession(session.id);
        row.put("fields", configured.stream().map(this::describeField).toList());
        row.put("seats", configured.stream().filter(TicketField::seat)
            .findFirst().map(TicketField::values).orElse(List.of()));
        row.put("tiers", configured.stream().filter(TicketField::tier)
            .findFirst().map(TicketField::values).orElse(List.of()));
        row.put("crowdBusyPercent", session.crowdBusyPercent);
        row.put("crowdSteadyPercent", session.crowdSteadyPercent);
        row.put("zoneCapacity", zoneCapacities.findBySession(session.id));
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
