package com.plink.ticket.venue;

import com.plink.ticket.service.TicketService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** The venue tab, as a holder reaches it from their registered ticket. */
@RestController
@RequestMapping("/api/tickets/{sessionId}/{token}/venue")
public class VenueController {
    private final TicketService tickets;
    private final VenueService venue;

    public VenueController(TicketService tickets, VenueService venue) {
        this.tickets = tickets;
        this.venue = venue;
    }

    @GetMapping
    public Map<String, Object> venue(@PathVariable long sessionId, @PathVariable String token, HttpSession session) {
        return venue.holder(tickets.resolve(sessionId, token), session);
    }

    @GetMapping("/images/{imageId}")
    public ResponseEntity<byte[]> image(@PathVariable long sessionId, @PathVariable String token,
            @PathVariable String imageId, HttpSession session) {
        return VenueAdminController.image(venue.holderImage(tickets.resolve(sessionId, token), session, imageId));
    }
}
