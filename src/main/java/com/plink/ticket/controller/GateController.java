package com.plink.ticket.controller;

import com.plink.ticket.model.EventSession;
import com.plink.ticket.model.Gate;
import com.plink.ticket.face.FaceService;
import com.plink.ticket.service.AdmissionService;
import com.plink.ticket.service.GateAuthService;
import com.plink.ticket.service.TicketService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
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

    public GateController(GateAuthService auth, AdmissionService admissions, TicketService tickets,
            FaceService faces) {
        this.auth = auth;
        this.admissions = admissions;
        this.tickets = tickets;
        this.faces = faces;
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
