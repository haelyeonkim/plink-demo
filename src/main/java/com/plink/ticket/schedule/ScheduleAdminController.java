package com.plink.ticket.schedule;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** The organiser's side of an event's days and passes. */
@RestController
@RequestMapping("/api/admin/sessions/{id}/schedule")
public class ScheduleAdminController {
    private final ScheduleService schedule;

    public ScheduleAdminController(ScheduleService schedule) { this.schedule = schedule; }

    @GetMapping
    public Map<String, Object> get(@PathVariable long id) {
        return schedule.console(id);
    }

    /** The whole list of days at once: the console edits them as one table. */
    @PutMapping("/days")
    public Map<String, Object> days(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return schedule.saveDays(id, body.get("days"));
    }

    @PostMapping("/passes")
    public Map<String, Object> createPass(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return schedule.createPass(id, body);
    }

    @PutMapping("/passes/{passId}")
    public Map<String, Object> updatePass(@PathVariable long id, @PathVariable long passId,
            @RequestBody Map<String, Object> body) {
        return schedule.updatePass(id, passId, body);
    }

    @DeleteMapping("/passes/{passId}")
    public Map<String, Object> deletePass(@PathVariable long id, @PathVariable long passId) {
        return schedule.deletePass(id, passId);
    }
}
