package com.plink.controller;

import com.plink.service.ArtworkRequestService;
import com.plink.service.ArtworkRequestInbox;
import org.springframework.security.core.Authentication;
import java.time.LocalDate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
public class ArtworkRequestController {
    private final ArtworkRequestService requests;
    private final ArtworkRequestInbox inbox;
    public ArtworkRequestController(ArtworkRequestService requests, ArtworkRequestInbox inbox) { this.requests = requests; this.inbox = inbox; }

    @GetMapping({"/api/links/s/{code}/artwork-requests", "/api/links/s/{slug}/{code}/artwork-requests"})
    public ResponseEntity<List<Map<String, Object>>> mine(@PathVariable String code, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(requests.mineWithNotices(code, request.getSession(false)));
    }
    @PostMapping({"/api/links/s/{code}/artwork-requests/{artworkId}", "/api/links/s/{slug}/{code}/artwork-requests/{artworkId}"})
    public Map<String, Object> act(@PathVariable String code, @PathVariable long artworkId,
            @RequestBody ArtworkRequestService.Command command, HttpServletRequest request) {
        return requests.view(requests.recipient(code, artworkId, command, request.getSession(false)));
    }
    @GetMapping({"/api/admin/artworks/requests", "/api/links/artwork-requests"})
    public ResponseEntity<Map<String, Object>> list(@RequestParam(defaultValue="all") String filter,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="") String search,
            @RequestParam(required=false) Long linkId, @RequestParam(required=false) Long artworkId,
            @RequestParam(defaultValue="") String processing, @RequestParam(defaultValue="") String owner,
            @RequestParam(required=false) LocalDate from, @RequestParam(required=false) LocalDate to,
            @RequestParam(defaultValue="false") boolean withdrawals, @RequestParam(defaultValue="oldest") String sort, Authentication auth) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(inbox.list(
            new ArtworkRequestInbox.Filter(filter, search, linkId, artworkId, processing, owner, from, to, withdrawals, sort, page), auth));
    }
    @GetMapping({"/api/admin/artworks/requests/{id}/history", "/api/links/artwork-requests/{id}/history"})
    public ResponseEntity<List<Map<String, Object>>> history(@PathVariable long id, Authentication auth) {
        inbox.check(id, auth);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(requests.history(id));
    }
    @GetMapping({"/api/admin/artworks/requests/{id}/recipient-history", "/api/links/artwork-requests/{id}/recipient-history"})
    public ResponseEntity<Map<String, Object>> recipientHistory(@PathVariable long id, @RequestParam(defaultValue="0") int page, Authentication auth) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(inbox.recipientHistory(id, page, auth));
    }
    @GetMapping({"/api/admin/artworks/requests/{id}/work-history", "/api/links/artwork-requests/{id}/work-history"})
    public ResponseEntity<Map<String, Object>> workHistory(@PathVariable long id, @RequestParam(defaultValue="0") int page, Authentication auth) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(inbox.workHistory(id, page, auth));
    }
    @PostMapping({"/api/admin/artworks/requests/{id}/work", "/api/links/artwork-requests/{id}/work"})
    public ResponseEntity<Void> work(@PathVariable long id, @RequestBody ArtworkRequestInbox.Work work, Authentication auth) {
        inbox.updateWork(id, work, auth); return ResponseEntity.noContent().build();
    }
    @PostMapping({"/api/admin/artworks/requests/{id}", "/api/links/artwork-requests/{id}"})
    public ArtworkRequestService.State admin(@PathVariable long id, @RequestBody ArtworkRequestService.Command command, Authentication auth) {
        inbox.check(id, auth);
        return requests.admin(id, command, ArtworkRequestService.currentActor());
    }
}
