package com.plink.ticket.controller;

import com.plink.ticket.model.EventSession;
import com.plink.ticket.model.Gate;
import com.plink.ticket.face.FaceService;
import com.plink.ticket.service.AdmissionService;
import com.plink.ticket.service.GateAuthService;
import com.plink.ticket.service.OfflineSyncService;
import com.plink.ticket.service.TicketService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gate terminal API. Authenticated by the terminal's own token, which also carries the
 * direction — the scanned code never gets to claim whether it is an entry or an exit.
 */
@RestController
@RequestMapping("/api/gates/{gateId}")
public class GateController {
    private final GateAuthService auth;
    private final AdmissionService admissions;
    private final TicketService tickets;
    private final FaceService faces;
    private final OfflineSyncService offline;

    public GateController(GateAuthService auth, AdmissionService admissions, TicketService tickets,
            FaceService faces, OfflineSyncService offline) {
        this.auth = auth;
        this.admissions = admissions;
        this.tickets = tickets;
        this.faces = faces;
        this.offline = offline;
    }

    @GetMapping
    public Map<String, Object> info(@PathVariable String gateId,
            @RequestHeader(value = "X-Gate-Token", required = false) String token) {
        Gate gate = auth.authenticate(gateId, token);
        EventSession session = tickets.requireSession(gate.sessionId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("gateId", gate.id);
        result.put("label", gate.label);
        result.put("zone", gate.zone);
        result.put("direction", gate.direction);
        result.put("sessionId", session.id);
        result.put("sessionName", session.name);
        result.put("exitScanRequired", session.exitScanRequired);
        return result;
    }

    /**
     * A challenge for the terminal to render as a light pattern. The server keeps the
     * expected value and grades the response, so a terminal cannot pass itself.
     */
    @GetMapping("/face/challenge")
    public Map<String, Object> faceChallenge(@PathVariable String gateId,
            @RequestHeader(value = "X-Gate-Token", required = false) String token) {
        auth.authenticate(gateId, token);
        return Map.of("challenge", com.plink.ticket.service.Secrets.randomAlnum(10),
            "issuedAt", java.time.Instant.now().toString());
    }

    /** Face track: 1:N inside the session, then the same movement rules as a scan. */
    @PostMapping("/face")
    public Map<String, Object> face(@PathVariable String gateId,
            @RequestHeader(value = "X-Gate-Token", required = false) String token,
            @RequestBody Map<String, Object> body) {
        Gate gate = auth.authenticate(gateId, token);
        EventSession session = tickets.requireSession(gate.sessionId);
        String challenge = body.get("challenge") == null ? null : String.valueOf(body.get("challenge"));
        try {
            FaceService.Match match = faces.identify(session, Frames.decode(body.get("frames")),
                challenge, gate.id);
            // Direction is left to the terminal, exactly as on the QR path.
            return admissions.move(gate, faces.ticketFor(match), null, "FACE", null, () -> { });
        } catch (ResponseStatusException refused) {
            admissions.recordDenied(gate, null, "FACE", refused.getReason());
            throw refused;
        }
    }

    /**
     * Hands back what the movements the terminal queued while offline. Nothing here is
     * accepted on the terminal's word: each code is re-verified and anything that
     * contradicts the ledger is flagged for investigation instead of applied.
     */
    @PostMapping("/sync")
    public Map<String, Object> sync(@PathVariable String gateId,
            @RequestHeader(value = "X-Gate-Token", required = false) String token,
            @RequestBody Map<String, Object> body) {
        Gate gate = auth.authenticate(gateId, token);
        Object events = body.get("events");
        if (!(events instanceof List<?> list)) {
            return Map.of("received", 0, "applied", 0, "flagged", 0, "conflicts", List.of());
        }
        List<Map<String, Object>> queued = new java.util.ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> event = new java.util.LinkedHashMap<>();
                map.forEach((key, value) -> event.put(String.valueOf(key), value));
                queued.add(event);
            }
        }
        return offline.replay(gate, queued);
    }

    @PostMapping("/scan")
    public Map<String, Object> scan(@PathVariable String gateId,
            @RequestHeader(value = "X-Gate-Token", required = false) String token,
            @RequestBody Map<String, String> body) {
        Gate gate = auth.authenticate(gateId, token);
        String code = body.get("code");
        String method = body.getOrDefault("method", "QR");
        if (!"QR".equals(method) && !"FACE".equals(method)) method = "QR";
        try {
            return admissions.admit(gate, code, method);
        } catch (ResponseStatusException refused) {
            // Refusals are part of the ledger: they are the raw material for
            // detecting shared codes and mis-signed terminals.
            admissions.recordDenied(gate, code, method, refused.getReason());
            throw refused;
        }
    }
}
