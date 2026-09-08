package com.plink.controller;

import com.plink.model.LinkView;
import com.plink.model.ProtectedLink;
import com.plink.service.LinkService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.util.*;

@RestController
@RequestMapping("/api/links")
public class LinkController {
    private final LinkService linkService;

    public LinkController(LinkService linkService) {
        this.linkService = linkService;
    }

    @GetMapping
    public List<Map<String, Object>> listLinks() {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (ProtectedLink link : linkService.getAllLinks()) {
            result.add(toSummary(link));
        }
        return result;
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getLink(@PathVariable Long id) {
        Optional<ProtectedLink> optLink = linkService.getLink(id);
        if (!optLink.isPresent()) {
            return ResponseEntity.notFound().build();
        }
        ProtectedLink link = optLink.get();
        Map<String, Object> body = toSummary(link);

        List<Map<String, Object>> views = new ArrayList<Map<String, Object>>();
        for (LinkView v : linkService.getViews(id)) {
            Map<String, Object> vm = new LinkedHashMap<String, Object>();
            vm.put("viewerName", v.getViewerName());
            vm.put("viewedAt", v.getViewedAt());
            views.add(vm);
        }
        body.put("views", views);
        return ResponseEntity.ok(body);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createLink(@RequestBody Map<String, Object> req) {
        String originalUrl = (String) req.get("originalUrl");
        if (originalUrl == null || originalUrl.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        String title = (String) req.get("title");
        String password = (String) req.get("password");
        String recipientNames = (String) req.get("recipientNames");

        Timestamp expiresAt = null;
        if (req.get("expiresAt") != null) {
            expiresAt = Timestamp.valueOf((String) req.get("expiresAt"));
        }

        int maxViews = 0;
        if (req.get("maxViews") != null) {
            maxViews = ((Number) req.get("maxViews")).intValue();
        }

        ProtectedLink link = linkService.createLink(originalUrl, title, password, expiresAt, recipientNames, maxViews);
        return ResponseEntity.status(HttpStatus.CREATED).body(toSummary(link));
    }

    @GetMapping("/s/{shortCode}")
    public ResponseEntity<Map<String, Object>> accessLink(@PathVariable String shortCode) {
        Optional<ProtectedLink> optLink = linkService.getLinkByShortCode(shortCode);
        if (!optLink.isPresent()) {
            return ResponseEntity.notFound().build();
        }
        ProtectedLink link = optLink.get();
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("shortCode", link.getShortCode());
        body.put("title", link.getTitle());
        body.put("hasPassword", link.hasPassword());
        body.put("expired", link.isExpired());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/s/{shortCode}/verify")
    public ResponseEntity<Map<String, Object>> verifyLink(
            @PathVariable String shortCode, @RequestBody Map<String, String> req) {
        String password = req.get("password");
        String viewerName = req.get("viewerName");
        Optional<String> url = linkService.verifyAndAccess(shortCode, password, viewerName);
        if (!url.isPresent()) {
            Map<String, Object> err = new LinkedHashMap<String, Object>();
            err.put("error", "Access denied");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(err);
        }
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("originalUrl", url.get());
        return ResponseEntity.ok(body);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteLink(@PathVariable Long id) {
        linkService.deleteLink(id);
        return ResponseEntity.noContent().build();
    }

    private Map<String, Object> toSummary(ProtectedLink link) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("id", link.getId());
        m.put("shortCode", link.getShortCode());
        m.put("originalUrl", link.getOriginalUrl());
        m.put("title", link.getTitle());
        m.put("hasPassword", link.hasPassword());
        m.put("expiresAt", link.getExpiresAt());
        m.put("recipientNames", link.getRecipientNames());
        m.put("maxViews", link.getMaxViews());
        m.put("viewCount", link.getViewCount());
        m.put("createdAt", link.getCreatedAt());
        return m;
    }
}
