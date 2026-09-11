package com.plink.ticket.service;

import com.plink.ticket.config.TicketProperties;
import com.plink.ticket.model.Gate;
import com.plink.ticket.repository.GateRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Gate terminals authenticate with a per-terminal token issued once at registration.
 * Only its keyed hash is stored, and the terminal's own row carries the direction and
 * zone, so a terminal cannot claim to be a different gate.
 */
@Service
public class GateAuthService {
    private final GateRepository gates;
    private final TicketProperties properties;

    public GateAuthService(GateRepository gates, TicketProperties properties) {
        this.gates = gates;
        this.properties = properties;
    }

    public String hash(String token) {
        return Secrets.hmacHex(properties.getTokenSecret(), "gate-token|" + token);
    }

    public Gate authenticate(String gateId, String token) {
        Gate gate = gates.findById(gateId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "등록되지 않은 단말이에요."));
        if (token == null || !Secrets.constantEquals(gate.tokenHmac, hash(token))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "단말 인증에 실패했어요.");
        }
        return gate;
    }

    public static String direction(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (!"IN".equals(value) && !"OUT".equals(value) && !"BIDIRECTIONAL".equals(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "게이트 방향은 IN, OUT, BIDIRECTIONAL 중 하나여야 해요.");
        }
        return value;
    }
}
