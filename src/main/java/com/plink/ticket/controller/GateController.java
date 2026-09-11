package com.plink.ticket.controller;

import com.plink.ticket.model.EventSession;
import com.plink.ticket.model.Gate;
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

    public GateController(GateAuthService auth, AdmissionService admissions, TicketService tickets) {
        this.auth = auth;
        this.admissions = admissions;
        this.tickets = tickets;
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
