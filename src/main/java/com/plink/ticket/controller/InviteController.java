package com.plink.ticket.controller;

import com.plink.ticket.service.InviteService;
import com.plink.ticket.service.TicketService;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** A holder's invites, and the group they make, as reached from the holder's ticket. */
@RestController
@RequestMapping("/api/tickets/{sessionId}/{token}/invites")
public class InviteController {
    private final TicketService tickets;
    private final InviteService invites;

    public InviteController(TicketService tickets, InviteService invites) {
        this.tickets = tickets;
        this.invites = invites;
    }

    @GetMapping
    public Map<String, Object> view(@PathVariable long sessionId, @PathVariable String token, HttpSession session) {
        return invites.view(tickets.resolve(sessionId, token), session);
    }

    @PostMapping
    public Map<String, Object> invite(@PathVariable long sessionId, @PathVariable String token,
            @RequestBody Map<String, Object> body, HttpSession session) {
        return invites.invite(tickets.resolve(sessionId, token), session,
            body.get("email") == null ? null : body.get("email").toString());
    }

    @DeleteMapping("/{ticketId}")
    public Map<String, Object> cancel(@PathVariable long sessionId, @PathVariable String token,
            @PathVariable long ticketId, HttpSession session) {
        return invites.cancel(tickets.resolve(sessionId, token), session, ticketId);
    }
}
