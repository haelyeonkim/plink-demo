package com.plink.ticket.service;

import com.plink.ticket.config.TicketProperties;
import com.plink.ticket.model.Gate;
import com.plink.ticket.repository.GateRepository;
import java.time.Duration;
import java.time.Instant;
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

    /** A terminal that has gone quiet this long can be replaced by another. */
    static final Duration TAKEOVER_AFTER = Duration.ofMinutes(10);

    public Gate authenticate(String gateId, String token) {
        return authenticate(gateId, token, null);
    }

    /**
     * Authenticates the terminal and holds the gate to a single device.
     *
     * <p>Two tablets sharing one gate id would each see half the movements, and the
     * presence ledger would read as if people teleported between them. So the first
     * terminal to connect claims the gate, and another can only take it over once the
     * first has been silent for {@link #TAKEOVER_AFTER} or an operator releases it.
     */
    public Gate authenticate(String gateId, String token, String deviceId) {
        Gate gate = gates.findById(gateId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "등록되지 않은 단말이에요."));
        if (token == null || !Secrets.constantEquals(gate.tokenHmac, hash(token))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "단말 인증에 실패했어요.");
        }
        if (deviceId == null || deviceId.isBlank()) {
            return gate;
        }
        if (gate.boundDevice == null) {
            gates.bindDevice(gateId, deviceId);
            gate.boundDevice = deviceId;
            return gate;
        }
        if (gate.boundDevice.equals(deviceId)) {
            gates.touch(gateId);
            return gate;
        }
        boolean stale = gate.lastSeenAt == null
            || gate.lastSeenAt.toInstant().isBefore(Instant.now().minus(TAKEOVER_AFTER));
        if (!stale) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "이 게이트는 다른 단말에서 사용 중이에요. 관리 화면에서 연결을 해제한 뒤 다시 시도해 주세요.");
        }
        gates.bindDevice(gateId, deviceId);
        gate.boundDevice = deviceId;
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
