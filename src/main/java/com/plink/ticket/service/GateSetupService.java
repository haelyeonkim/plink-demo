package com.plink.ticket.service;

import com.plink.ticket.config.TicketProperties;
import com.plink.ticket.model.Gate;
import com.plink.ticket.repository.GateRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Enrols a gate terminal from a link and a code, the same two-channel shape the ticket
 * claim uses.
 *
 * <p>The link identifies the gate and the code proves whoever holds the tablet was told
 * it separately, so a setup link pasted into a group chat is not enough on its own. The
 * long-lived terminal token is never typed by a person: it is handed to the tablet once
 * the code checks out, and bound to that device in the same step.
 */
@Service
public class GateSetupService {
    static final Duration SETUP_TTL = Duration.ofHours(24);
    static final int MAX_ATTEMPTS = 5;

    private final GateRepository gates;
    private final GateAuthService auth;
    private final TextCipher cipher;
    private final TicketProperties properties;
    private final String baseUrl;
    private final SecureRandom random = new SecureRandom();

    public GateSetupService(GateRepository gates, GateAuthService auth, TextCipher cipher,
            TicketProperties properties, @Value("${plink.auth.base-url}") String baseUrl) {
        this.gates = gates;
        this.auth = auth;
        this.cipher = cipher;
        this.properties = properties;
        this.baseUrl = baseUrl;
    }

    public String setupUrl(String token) {
        return baseUrl + "/tickets/gate/" + token;
    }

    String hashToken(String token) {
        return Secrets.hmacHex(properties.getTokenSecret(), "gate-setup|" + token);
    }

    private String hashCode(String gateId, String code) {
        return Secrets.hmacHex(properties.getTokenSecret(), "gate-code|" + gateId + "|" + code);
    }

    /** Issues a fresh setup link and code, replacing any outstanding one. */
    @Transactional
    public Map<String, Object> open(String gateId) {
        Gate gate = gates.findById(gateId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "게이트를 찾을 수 없어요."));
        String token = Secrets.randomToken(16);
        String code = String.format("%06d", random.nextInt(1_000_000));
        Timestamp expiresAt = Timestamp.from(Instant.now().plus(SETUP_TTL));
        gates.saveSetup(gate.id, hashToken(token), cipher.seal(token),
            hashCode(gate.id, code), cipher.seal(code), expiresAt);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("gateId", gate.id);
        result.put("setupUrl", setupUrl(token));
        result.put("setupCode", code);
        result.put("expiresAt", expiresAt.toInstant().toString());
        return result;
    }

    /** What the terminal shows before anyone types the code. Carries no secret. */
    public Map<String, Object> describe(String token) {
        Gate gate = require(token);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("gateId", gate.id);
        result.put("label", gate.label);
        result.put("zone", gate.zone);
        result.put("direction", gate.direction);
        result.put("expiresAt", gate.setupExpiresAt.toInstant().toString());
        result.put("deviceBound", gate.boundDevice != null);
        return result;
    }

    /**
     * Exchanges the code for the terminal token and claims the gate for this device.
     * The setup link stays usable until it expires, so a replacement tablet can be set up
     * without going back to the console - but only after the current one is released.
     */
    @Transactional
    public Map<String, Object> complete(String token, String code, String deviceId) {
        Gate gate = require(token);
        if (deviceId == null || deviceId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "단말 정보를 확인할 수 없어요.");
        }
        if (gate.setupAttempts >= MAX_ATTEMPTS) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "인증 시도가 많아 잠겼어요. 관리 화면에서 설정 링크를 새로 발급해 주세요.");
        }
        if (code == null || !code.trim().matches("\\d{6}")
                || !Secrets.constantEquals(gate.setupCodeHash, hashCode(gate.id, code.trim()))) {
            gates.countSetupAttempt(gate.id);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "인증번호가 일치하지 않아요.");
        }
        if (gate.boundDevice != null && !gate.boundDevice.equals(deviceId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "이 게이트는 다른 단말에서 사용 중이에요. 관리 화면에서 연결을 해제한 뒤 다시 시도해 주세요.");
        }
        String terminalToken = cipher.open(gate.tokenCipher);
        if (terminalToken == null) {
            // The stored token cannot be read back, so mint one rather than stranding the
            // terminal; the previous tablet would have to be set up again anyway.
            terminalToken = Secrets.randomToken(24);
            gates.rotateToken(gate.id, auth.hash(terminalToken), cipher.seal(terminalToken));
        }
        gates.bindDevice(gate.id, deviceId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("gateId", gate.id);
        result.put("gateToken", terminalToken);
        result.put("label", gate.label);
        result.put("direction", gate.direction);
        return result;
    }

    private Gate require(String token) {
        Gate gate = token == null || token.isBlank() ? null
            : gates.findBySetupToken(hashToken(token)).orElse(null);
        if (gate == null || !gate.setupOpen()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "설정 링크를 찾을 수 없거나 기한이 지났어요.");
        }
        return gate;
    }
}
