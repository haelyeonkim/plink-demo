package com.plink.ticket.controller;

import com.plink.activity.DataQueryService;
import com.plink.ticket.service.TicketService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * One event's numbers for its own console: the figures the data console's overview shows,
 * for this event only. Counts and names of gates and booths - nobody's contact - so whoever
 * may run the event may read them; the service's logs stay with the data console.
 */
@RestController
@RequestMapping("/api/admin/sessions/{id}/stats")
public class EventStatsController {
    private final TicketService tickets;
    private final DataQueryService data;

    public EventStatsController(TicketService tickets, DataQueryService data) {
        this.tickets = tickets;
        this.data = data;
    }

    @GetMapping
    public Map<String, Object> stats(@PathVariable long id,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        tickets.requireSession(id);
        Map<String, Object> overview = data.overview(DataQueryService.Range.of(id, from, to));
        overview.remove("logs");
        return overview;
    }
}
