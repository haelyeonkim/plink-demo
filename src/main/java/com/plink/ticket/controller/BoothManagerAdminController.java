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
        return managers.invite(boothId, email == null ? null : String.valueOf(email),
            Boolean.TRUE.equals(body.get("notify")));
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
