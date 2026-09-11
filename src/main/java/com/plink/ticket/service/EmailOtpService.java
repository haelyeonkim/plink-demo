package com.plink.ticket.service;

import com.plink.ticket.config.TicketProperties;
import com.plink.ticket.repository.EmailOtpRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * Six-digit email verification. Only a keyed hash of the code is stored, the newest
 * code invalidates older ones, and both attempts and resend rate are capped.
 */
@Service
public class EmailOtpService {
    static final Duration TTL = Duration.ofMinutes(10);
    static final Duration RESEND_INTERVAL = Duration.ofSeconds(60);
    static final int MAX_ATTEMPTS = 5;

    private final EmailOtpRepository repository;
    private final EmailSender mail;
    private final TicketProperties properties;
    private final SecureRandom random = new SecureRandom();

    public EmailOtpService(EmailOtpRepository repository, EmailSender mail, TicketProperties properties) {
        this.repository = repository;
        this.mail = mail;
        this.properties = properties;
    }

    public static String normalize(String email) {
        if (email == null || email.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이메일 주소를 입력해 주세요.");
        }
        String value = email.trim().toLowerCase(Locale.ROOT);
        if (value.length() > 255 || !value.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이메일 주소 형식을 확인해 주세요.");
        }
        return value;
    }

    public void issue(String email, String purpose, String subject, String intro) {
        Optional<EmailOtpRepository.Otp> previous = repository.findLatest(email, purpose);
        if (previous.isPresent()
                && previous.get().createdAt.toInstant().isAfter(Instant.now().minus(RESEND_INTERVAL))) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "잠시 후에 다시 요청해 주세요.");
        }
        repository.consumeAll(email, purpose);
        String code = String.format("%06d", random.nextInt(1_000_000));
        repository.insert(email, purpose, hash(email, purpose, code),
            Timestamp.from(Instant.now().plus(TTL)));
        mail.send(email, subject, intro + "\n\n인증번호: " + code + "\n\n"
            + TTL.toMinutes() + "분 안에 입력해 주세요. 요청하지 않았다면 이 메일을 무시하세요.");
    }

    /** Consumes the code on success; counts the attempt on failure. */
    public void verify(String email, String purpose, String code) {
        if (code == null || !code.trim().matches("\\d{6}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "6자리 인증번호를 입력해 주세요.");
        }
        EmailOtpRepository.Otp otp = repository.findLatest(email, purpose)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "인증번호를 먼저 요청해 주세요."));
        if (otp.consumedAt != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이미 사용한 인증번호예요. 다시 요청해 주세요.");
        }
        if (otp.expiresAt.toInstant().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "인증번호 유효 시간이 지났어요. 다시 요청해 주세요.");
        }
        if (otp.attempts >= MAX_ATTEMPTS) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "시도 횟수를 초과했어요. 다시 요청해 주세요.");
        }
        if (!Secrets.constantEquals(otp.codeHash, hash(email, purpose, code.trim()))) {
            repository.incrementAttempts(otp.id);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "인증번호가 일치하지 않아요.");
        }
        repository.consume(otp.id);
    }

    private String hash(String email, String purpose, String code) {
        return Secrets.hmacHex(properties.getTokenSecret(), "otp|" + purpose + "|" + email + "|" + code);
    }
}
