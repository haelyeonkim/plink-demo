package com.plink.ticket.community;

import com.plink.ticket.service.TicketService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** The room's chat and board, as a holder reaches them from their ticket. */
@RestController
@RequestMapping("/api/tickets/{sessionId}/{token}/community")
public class CommunityController {
    private final TicketService tickets;
    private final CommunityService community;

    public CommunityController(TicketService tickets, CommunityService community) {
        this.tickets = tickets;
        this.community = community;
    }

    /** Whether the channels are open to this holder yet, and what to show on their tiles. */
    @GetMapping
    public Map<String, Object> summary(@PathVariable long sessionId, @PathVariable String token, HttpSession session) {
        return community.summary(tickets.resolve(sessionId, token), session);
    }

    @GetMapping("/chat")
    public Map<String, Object> chat(@PathVariable long sessionId, @PathVariable String token,
            @RequestParam(defaultValue = "0") long after, HttpSession session) {
        return community.chat(tickets.resolve(sessionId, token), session, after);
    }

    @PostMapping("/chat")
    public Map<String, Object> post(@PathVariable long sessionId, @PathVariable String token,
            @RequestBody Map<String, Object> body, HttpSession session) {
        Long replyTo = body.get("replyTo") instanceof Number n ? n.longValue() : null;
        return community.post(tickets.resolve(sessionId, token), session,
            body.get("body") == null ? null : body.get("body").toString(), replyTo);
    }

    @PostMapping("/chat/{messageId}/like")
    public Map<String, Object> like(@PathVariable long sessionId, @PathVariable String token,
            @PathVariable long messageId, HttpSession session) {
        return community.like(tickets.resolve(sessionId, token), session, messageId);
    }

    @PostMapping("/chat/{messageId}/report")
    public Map<String, Object> report(@PathVariable long sessionId, @PathVariable String token,
            @PathVariable long messageId, HttpSession session) {
        return community.report(tickets.resolve(sessionId, token), session, messageId);
    }

    @GetMapping("/ads")
    public List<Map<String, Object>> ads(@PathVariable long sessionId, @PathVariable String token, HttpSession session) {
        return community.ads(tickets.resolve(sessionId, token), session);
    }

    @GetMapping("/ads/{adId}")
    public Map<String, Object> ad(@PathVariable long sessionId, @PathVariable String token,
            @PathVariable long adId, HttpSession session) {
        return community.ad(tickets.resolve(sessionId, token), session, adId);
    }

    @GetMapping("/ads/{adId}/image")
    public ResponseEntity<byte[]> image(@PathVariable long sessionId, @PathVariable String token,
            @PathVariable long adId, HttpSession session) {
        return AdImages.response(community.holderImage(tickets.resolve(sessionId, token), session, adId));
    }
}
