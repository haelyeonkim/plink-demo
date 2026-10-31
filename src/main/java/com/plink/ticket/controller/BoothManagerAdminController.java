package com.plink.ticket.controller;

import com.plink.ticket.service.BoothManagerService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** The console's side of booth managers: who may run each booth, and taking that back. */
@RestController
@RequestMapping("/api/admin")
public class BoothManagerAdminController {
    private final BoothManagerService managers;

    public BoothManagerAdminController(BoothManagerService managers) {
        this.managers = managers;
    }

    @GetMapping("/booths/{boothId}/managers")
    public List<Map<String, Object>> list(@PathVariable long boothId) {
        return managers.list(boothId);
    }

    /** The link comes back once, here, so the organiser can hand it over themselves. */
    @PostMapping("/booths/{boothId}/managers")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> invite(@PathVariable long boothId, @RequestBody Map<String, Object> body) {
        Object email = body.get("email");
        // The terminal they answer for: one of the booth's, or {label} for a new one.
        String newTerminal = null;
        if (body.get("newTerminal") instanceof Map<?, ?> fresh) {
            newTerminal = fresh.get("label") == null ? "" : String.valueOf(fresh.get("label"));
        }
        return managers.invite(boothId, email == null ? null : String.valueOf(email),
            Boolean.TRUE.equals(body.get("notify")), string(body.get("gateId")), newTerminal);
    }

    /** Which terminal a manager answers for; a null gateId leaves them with none. */
    @PutMapping("/booth-managers/{managerId}")
    public Map<String, Object> assign(@PathVariable long managerId, @RequestBody Map<String, Object> body) {
        return managers.assign(managerId, string(body.get("gateId")));
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @PostMapping("/booth-managers/{managerId}/reissue")
    public Map<String, Object> reissue(@PathVariable long managerId,
            @RequestBody(required = false) Map<String, Object> body) {
        return managers.reissue(managerId, body != null && Boolean.TRUE.equals(body.get("notify")));
    }

    @DeleteMapping("/booth-managers/{managerId}")
    public Map<String, Object> revoke(@PathVariable long managerId) {
        return managers.revoke(managerId);
    }
}
