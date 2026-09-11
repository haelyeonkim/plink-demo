package com.plink.ticket.controller;

import com.plink.ticket.model.Ticket;
import com.plink.ticket.repository.TicketPasskeyRepository;
import com.plink.ticket.service.EmailOtpService;
import com.plink.ticket.service.PresentationService;
import com.plink.ticket.service.TicketPasskeyService;
import com.plink.ticket.service.TicketService;
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
 */
@RestController
@RequestMapping("/api/t/{sessionId}/{token}")
public class TicketController {
    private final TicketService tickets;
    private final TicketPasskeyService passkeyService;
    private final TicketPasskeyRepository passkeys;
    private final EmailOtpService otp;

    public TicketController(TicketService tickets, TicketPasskeyService passkeyService,
            TicketPasskeyRepository passkeys, EmailOtpService otp) {
        this.tickets = tickets;
        this.passkeyService = passkeyService;
        this.passkeys = passkeys;
        this.otp = otp;
    }

    @GetMapping
    public Map<String, Object> view(@PathVariable long sessionId, @PathVariable String token) {
        return tickets.view(tickets.resolve(sessionId, token));
    }

    /**
     * Sends a verification code. The address must match the one the ticket was issued
     * to, so claiming needs both the link and that mailbox.
     */
    @PostMapping("/otp")
    public Map<String, Object> requestOtp(@PathVariable long sessionId, @PathVariable String token,
            @RequestBody Map<String, String> body) {
        Ticket ticket = tickets.resolve(sessionId, token);
        String email = EmailOtpService.normalize(body.get("email"));
        boolean claimed = passkeys.findByTicketId(ticket.id).isPresent();
        String expected = claimed ? ticket.holderEmail : ticket.issuedToEmail;
        if (!email.equalsIgnoreCase(expected)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이 입장권에 등록된 이메일이 아니에요.");
        }
        otp.issue(email, purpose(ticket, claimed),
            claimed ? "입장권 재발급 인증번호" : "입장권 등록 인증번호",
            claimed ? "입장권을 새 기기로 옮기기 위한 인증번호예요." : "입장권 등록을 위한 인증번호예요.");
        return Collections.singletonMap("sent", true);
    }

    @PostMapping("/otp/verify")
    public Map<String, Object> verifyOtp(@PathVariable long sessionId, @PathVariable String token,
            @RequestBody Map<String, String> body, HttpSession session) {
        Ticket ticket = tickets.resolve(sessionId, token);
        String email = EmailOtpService.normalize(body.get("email"));
        boolean claimed = passkeys.findByTicketId(ticket.id).isPresent();
        otp.verify(email, purpose(ticket, claimed), body.get("code"));
        passkeyService.markEmailVerified(session, ticket.id, email);
        return Collections.singletonMap("verified", true);
    }

    /** Registration options when unclaimed, assertion options once bound. */
    @PostMapping("/passkey/options")
    public Map<String, Object> options(@PathVariable long sessionId, @PathVariable String token,
            @RequestBody(required = false) Map<String, String> body, HttpSession session) {
        Ticket ticket = tickets.resolve(sessionId, token);
        String direction = body == null ? null : body.get("direction");
        return passkeyService.start(ticket, direction, session);
    }

    /** Binds the passkey, or issues a presentation grant for the requested direction. */
    @PostMapping("/passkey/finish")
    public Map<String, Object> finish(@PathVariable long sessionId, @PathVariable String token,
            @RequestBody JsonNode credential, HttpSession session) {
        Ticket ticket = tickets.resolve(sessionId, token);
        return passkeyService.finish(ticket, credential, session);
    }

    /**
     * Lost or replaced device. One passkey per URL is an invariant, so recovery does not
     * add a second credential: it drops the binding and rotates the token, which kills
     * the old URL and mails a fresh one.
     */
    @PostMapping("/recover")
    public Map<String, Object> recover(@PathVariable long sessionId, @PathVariable String token,
            HttpSession session) {
        Ticket ticket = tickets.resolve(sessionId, token);
        String email = passkeyService.verifiedEmail(session, ticket.id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "이메일 인증을 먼저 완료해 주세요."));
        return tickets.reissue(ticket, email);
    }

    private String purpose(Ticket ticket, boolean claimed) {
        return (claimed ? "ticket-recover:" : "ticket-claim:") + ticket.id;
    }
}
