package com.plink.controller;

import com.plink.service.ArtworkRequestService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
public class ArtworkRequestController {
    private final ArtworkRequestService requests;
    public ArtworkRequestController(ArtworkRequestService requests) { this.requests = requests; }

    @GetMapping({"/api/links/s/{code}/artwork-requests", "/api/links/s/{slug}/{code}/artwork-requests"})
    public ResponseEntity<List<ArtworkRequestService.State>> mine(@PathVariable String code, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(requests.mine(code, request.getSession(false)));
    }
    @PostMapping({"/api/links/s/{code}/artwork-requests/{artworkId}", "/api/links/s/{slug}/{code}/artwork-requests/{artworkId}"})
    public ArtworkRequestService.State act(@PathVariable String code, @PathVariable long artworkId,
            @RequestBody ArtworkRequestService.Command command, HttpServletRequest request) {
        return requests.recipient(code, artworkId, command, request.getSession(false));
    }
    @GetMapping("/api/admin/artworks/requests")
    public ResponseEntity<Map<String, Object>> list(@RequestParam(defaultValue="all") String filter,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="") String search) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(requests.list(filter, page, search));
    }
    @GetMapping("/api/admin/artworks/requests/{id}/history")
    public ResponseEntity<List<Map<String, Object>>> history(@PathVariable long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(requests.history(id));
    }
    @PostMapping("/api/admin/artworks/requests/{id}")
    public ArtworkRequestService.State admin(@PathVariable long id, @RequestBody ArtworkRequestService.Command command) {
        return requests.admin(id, command, ArtworkRequestService.currentActor());
    }
}
