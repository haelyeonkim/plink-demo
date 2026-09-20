package com.plink.ticket.controller;

import com.plink.ticket.model.EventSession;
import com.plink.ticket.model.Gate;
import com.plink.ticket.face.FaceService;
import com.plink.ticket.service.AdmissionService;
import com.plink.ticket.service.GateAuthService;
import com.plink.ticket.service.GateSetupService;
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
@RequestMapping("/api/gates")
public class GateController {
    private final GateAuthService auth;
    private final AdmissionService admissions;
    private final TicketService tickets;
    private final FaceService faces;
    private final OfflineSyncService offline;
    private final GateSetupService setup;
    private final com.plink.ticket.repository.GateRepository gates;

    public GateController(GateAuthService auth, AdmissionService admissions, TicketService tickets,
            FaceService faces, OfflineSyncService offline, GateSetupService setup,
            com.plink.ticket.repository.GateRepository gates) {
        this.gates = gates;
        this.auth = auth;
        this.admissions = admissions;
        this.tickets = tickets;
        this.faces = faces;
        this.offline = offline;
        this.setup = setup;
    }

    /** What the setup link shows before the code is entered. Carries no secret. */
    @GetMapping("/setup/{setupToken}")
    public Map<String, Object> setupInfo(@PathVariable String setupToken) {
        return setup.describe(setupToken);
    }

    /** Exchanges the code for the terminal token and claims the gate for this device. */
    @PostMapping("/setup/{setupToken}")
    public Map<String, Object> completeSetup(@PathVariable String setupToken,
            @RequestBody Map<String, String> body) {
        return setup.complete(setupToken, body.get("code"), body.get("deviceId"));
    }

    @GetMapping("/{gateId}")
    public Map<String, Object> info(@PathVariable String gateId,
            @RequestHeader(value = "X-Gate-Token", required = false) String token,
            @RequestHeader(value = "X-Gate-Device", required = false) String device) {
        Gate gate = auth.authenticate(gateId, token, device);
        EventSession session = tickets.requireSession(gate.sessionId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("gateId", gate.id);
        result.put("label", gate.label);
        result.put("zone", gate.zone);
        result.put("direction", gate.direction);
        result.put("sessionId", session.id);
        result.put("sessionName", session.name);
        result.put("exitScanRequired", session.exitScanRequired);
        result.put("deviceBound", gate.boundDevice != null);
        result.put("tokenExpiresAt", gate.tokenExpiresAt == null ? null
            : gate.tokenExpiresAt.toInstant().toString());
        return result;
    }

    /**
     * The terminal renewing its own end date.
     *
     * <p>Only a terminal that is still valid and still holds the gate can ask, so this
     * is a tablet that has been working all week saying so, not a way back in for a
     * token that has already lapsed - that one has to go through the console.
     */
    @PostMapping("/{gateId}/renew")
    public Map<String, Object> renew(@PathVariable String gateId,
            @RequestHeader(value = "X-Gate-Token", required = false) String token,
            @RequestHeader(value = "X-Gate-Device", required = false) String device) {
        Gate gate = auth.authenticate(gateId, token, device);
        java.sql.Timestamp until = auth.tokenExpiry();
        gates.renewToken(gate.id, until);
        return Map.of("gateId", gate.id, "tokenExpiresAt", until.toInstant().toString());
    }

    /**
     * A challenge for the terminal to render as a light pattern. The server keeps the
     * expected value and grades the response, so a terminal cannot pass itself.
     */
    @GetMapping("/{gateId}/face/challenge")
    public Map<String, Object> faceChallenge(@PathVariable String gateId,
            @RequestHeader(value = "X-Gate-Token", required = false) String token) {
        auth.authenticate(gateId, token);
        return Map.of("challenge", com.plink.ticket.service.Secrets.randomAlnum(10),
            "issuedAt", java.time.Instant.now().toString());
    }

    /** Face track: 1:N inside the session, then the same movement rules as a scan. */
    @PostMapping("/{gateId}/face")
    public Map<String, Object> face(@PathVariable String gateId,
            @RequestHeader(value = "X-Gate-Token", required = false) String token,
            @RequestHeader(value = "X-Gate-Device", required = false) String device,
            @RequestBody Map<String, Object> body) {
        Gate gate = auth.authenticate(gateId, token, device);
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
    @PostMapping("/{gateId}/sync")
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

    @PostMapping("/{gateId}/scan")
    public Map<String, Object> scan(@PathVariable String gateId,
            @RequestHeader(value = "X-Gate-Token", required = false) String token,
            @RequestHeader(value = "X-Gate-Device", required = false) String device,
            @RequestBody Map<String, String> body) {
        Gate gate = auth.authenticate(gateId, token, device);
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
