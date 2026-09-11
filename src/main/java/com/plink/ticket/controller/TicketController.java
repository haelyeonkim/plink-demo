package com.plink.ticket.controller;

import com.plink.ticket.repository.TicketPasskeyRepository;
import com.plink.ticket.service.EmailOtpService;
import com.plink.ticket.service.TicketPasskeyService;
import com.plink.ticket.service.TicketService;
import com.plink.ticket.service.TransferService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.Map;

/**
 * The holder-facing ticket URL: {@code /t/{sessionId}/{personalToken}}.
 *
 * <p>The token identifies the ticket and, before it is claimed, is a bearer secret.
 * Everything after the claim requires the bound passkey, so the URL alone opens nothing.
 * The same path also serves a transfer recipient, whose claim token resolves to the
 * same ticket but means the opposite thing.
 */
@RestController
@RequestMapping("/api/t/{sessionId}/{token}")
public class TicketController {
    private final TicketService tickets;
    private final TicketPasskeyService passkeyService;
    private final TicketPasskeyRepository passkeys;
    private final TransferService transfers;
    private final EmailOtpService otp;

    public TicketController(TicketService tickets, TicketPasskeyService passkeyService,
            TicketPasskeyRepository passkeys, TransferService transfers, EmailOtpService otp) {
        this.tickets = tickets;
        this.passkeyService = passkeyService;
        this.passkeys = passkeys;
        this.transfers = transfers;
        this.otp = otp;
    }

    @GetMapping
    public Map<String, Object> view(@PathVariable long sessionId, @PathVariable String token) {
        return tickets.view(tickets.resolve(sessionId, token));
    }

    /**
     * Sends a verification code. The address must match the one this link was sent to, so
     * claiming needs both the link and that mailbox.
     */
    @PostMapping("/otp")
    public Map<String, Object> requestOtp(@PathVariable long sessionId, @PathVariable String token,
            @RequestBody Map<String, String> body) {
        TicketService.Resolved resolved = tickets.resolve(sessionId, token);
        String email = EmailOtpService.normalize(body.get("email"));
        if (!email.equalsIgnoreCase(expectedEmail(resolved))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이 링크에 등록된 이메일이 아니에요.");
        }
        boolean claimed = isClaimed(resolved);
        otp.issue(email, purpose(resolved),
            claimed ? "입장권 재발급 인증번호" : "입장권 등록 인증번호",
            resolved.viaTransfer() ? "양도받은 입장권을 등록하기 위한 인증번호예요."
                : claimed ? "입장권을 새 기기로 옮기기 위한 인증번호예요."
                : "입장권 등록을 위한 인증번호예요.");
        return Collections.singletonMap("sent", true);
    }

    @PostMapping("/otp/verify")
    public Map<String, Object> verifyOtp(@PathVariable long sessionId, @PathVariable String token,
            @RequestBody Map<String, String> body, HttpSession session) {
        TicketService.Resolved resolved = tickets.resolve(sessionId, token);
        String email = EmailOtpService.normalize(body.get("email"));
        otp.verify(email, purpose(resolved), body.get("code"));
        passkeyService.markEmailVerified(session, resolved.ticket.id, email);
        return Collections.singletonMap("verified", true);
    }

    /**
     * Registration options when unclaimed, assertion options once bound. The intent says
     * what the assertion is for: opening a QR, or signing off a transfer.
     */
    @PostMapping("/passkey/options")
    public Map<String, Object> options(@PathVariable long sessionId, @PathVariable String token,
            @RequestBody(required = false) Map<String, String> body, HttpSession session) {
        TicketService.Resolved resolved = tickets.resolve(sessionId, token);
        Map<String, String> input = body == null ? Map.of() : body;
        return passkeyService.start(resolved, input.get("intent"), input.get("direction"),
            input.get("toEmail"), session);
    }

    /** Binds the passkey, issues a presentation grant, or records the transfer request. */
    @PostMapping("/passkey/finish")
    public Map<String, Object> finish(@PathVariable long sessionId, @PathVariable String token,
            @RequestBody JsonNode credential, HttpSession session, HttpServletRequest request) {
        TicketService.Resolved resolved = tickets.resolve(sessionId, token);
        return passkeyService.finish(resolved, credential, session,
            request.getRemoteAddr(), trim(request.getHeader("User-Agent")));
    }

    /** The sender can call off a transfer until the recipient finishes registering. */
    @PostMapping("/transfer/cancel")
    public Map<String, Object> cancelTransfer(@PathVariable long sessionId, @PathVariable String token,
            HttpSession session) {
        TicketService.Resolved resolved = tickets.resolve(sessionId, token);
        if (resolved.viaTransfer()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "보낸 사람만 취소할 수 있어요.");
        }
        // Cancelling gives the ticket back, so it needs the same mailbox proof as a claim.
        passkeyService.verifiedEmail(session, resolved.ticket.id)
            .filter(email -> email.equalsIgnoreCase(resolved.ticket.holderEmail))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                "이메일 인증을 먼저 완료해 주세요."));
        return transfers.cancel(resolved.ticket);
    }

    /**
     * Lost or replaced device. One passkey per URL is an invariant, so recovery does not
     * add a second credential: it drops the binding and rotates the token, which kills
     * the old URL and mails a fresh one.
     */
    @PostMapping("/recover")
    public Map<String, Object> recover(@PathVariable long sessionId, @PathVariable String token,
            HttpSession session) {
        TicketService.Resolved resolved = tickets.resolve(sessionId, token);
        if (resolved.viaTransfer()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "양도 링크에서는 재발급할 수 없어요.");
        }
        String email = passkeyService.verifiedEmail(session, resolved.ticket.id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "이메일 인증을 먼저 완료해 주세요."));
        return tickets.reissue(resolved.ticket, email);
    }

    private boolean isClaimed(TicketService.Resolved resolved) {
        return !resolved.viaTransfer() && passkeys.findByTicketId(resolved.ticket.id).isPresent();
    }

    private String expectedEmail(TicketService.Resolved resolved) {
        if (resolved.viaTransfer()) return resolved.claim.toEmail;
        return isClaimed(resolved) ? resolved.ticket.holderEmail : resolved.ticket.issuedToEmail;
    }

    private String purpose(TicketService.Resolved resolved) {
        if (resolved.viaTransfer()) return "ticket-transfer:" + resolved.claim.id;
        return (isClaimed(resolved) ? "ticket-recover:" : "ticket-claim:") + resolved.ticket.id;
    }

    private static String trim(String value) {
        if (value == null) return null;
        return value.length() > 255 ? value.substring(0, 255) : value;
    }
}
