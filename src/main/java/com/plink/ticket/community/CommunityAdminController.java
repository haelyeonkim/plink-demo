package com.plink.ticket.community;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/** The organiser's side of the room's chat and board. */
@RestController
@RequestMapping("/api/admin/sessions/{id}")
public class CommunityAdminController {
    private final CommunityService community;

    public CommunityAdminController(CommunityService community) { this.community = community; }

    @GetMapping("/chat")
    public Map<String, Object> chat(@PathVariable long id, @RequestParam(defaultValue = "ALL") String filter) {
        return community.consoleChat(id, filter);
    }

    /** A notice from the organiser, held at the top of the chat unless said otherwise. */
    @PostMapping("/chat/notice")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> notice(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return community.notice(id, body.get("body") == null ? null : body.get("body").toString(),
            !Boolean.FALSE.equals(body.get("pin")), Boolean.TRUE.equals(body.get("alsoAd")),
            Boolean.TRUE.equals(body.get("alert")));
    }

    /** Takes a notice off the holders' tickets, or raises it there again. */
    @PostMapping("/chat/{messageId}/alert")
    public Map<String, Object> alert(@PathVariable long id, @PathVariable long messageId, @RequestBody Map<String, Object> body) {
        boolean alert = Boolean.TRUE.equals(body.get("alert"));
        community.setAlert(id, messageId, alert);
        return Map.of("alert", alert);
    }

    @PostMapping("/chat/{messageId}/hidden")
    public Map<String, Object> hide(@PathVariable long id, @PathVariable long messageId, @RequestBody Map<String, Object> body) {
        boolean hidden = Boolean.TRUE.equals(body.get("hidden"));
        community.setHidden(id, messageId, hidden);
        return Map.of("hidden", hidden);
    }

    @PostMapping("/chat/{messageId}/pinned")
    public Map<String, Object> pin(@PathVariable long id, @PathVariable long messageId, @RequestBody Map<String, Object> body) {
        boolean pinned = Boolean.TRUE.equals(body.get("pinned"));
        community.setPinned(id, messageId, pinned);
        return Map.of("pinned", pinned);
    }

    /** Stops one ticket writing to the chat, or lets it again. It can still read. */
    @PostMapping("/chat/mute")
    public Map<String, Object> mute(@PathVariable long id, @RequestBody Map<String, Object> body) {
        if (!(body.get("ticketId") instanceof Number ticketId)) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "입장권을 골라 주세요.");
        }
        boolean muted = Boolean.TRUE.equals(body.get("muted"));
        community.setMuted(id, ticketId.longValue(), muted);
        return Map.of("muted", muted);
    }

    /** The people who have written in the chat, or been muted or banned, a page at a time. */
    @GetMapping("/chat/people")
    public Map<String, Object> people(@PathVariable long id, @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return community.chatPeople(id, q, page, size);
    }

    /** Puts a ticket out of the chat - no writing, and its lines kept from holders - or lets it back. */
    @PostMapping("/chat/ban")
    public Map<String, Object> ban(@PathVariable long id, @RequestBody Map<String, Object> body) {
        if (!(body.get("ticketId") instanceof Number ticketId)) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "입장권을 골라 주세요.");
        }
        return community.setBanned(id, ticketId.longValue(), Boolean.TRUE.equals(body.get("banned")));
    }

    @PutMapping("/chat/settings")
    public Map<String, Object> settings(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return community.saveSettings(id, body);
    }

    /** The board's notices: ALL, or those under one review status (PENDING, PUBLISHED, REJECTED). */
    @GetMapping("/ads")
    public List<Map<String, Object>> ads(@PathVariable long id, @RequestParam(defaultValue = "ALL") String status) {
        return community.consoleAds(id, status);
    }

    /** How many booth notices wait for the organiser, for the menu's badge. */
    @GetMapping("/ads/summary")
    public Map<String, Object> adSummary(@PathVariable long id) {
        return community.adSummary(id);
    }

    /** Approves a booth's notice onto holders' boards, or turns it back with a reason. */
    @PostMapping("/ads/{adId}/review")
    public Map<String, Object> review(@PathVariable long id, @PathVariable long adId, @RequestBody Map<String, Object> body) {
        return community.review(id, adId, body.get("decision") == null ? null : body.get("decision").toString(),
            body.get("reason") == null ? null : body.get("reason").toString());
    }

    @PostMapping("/ads")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createAd(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return community.createAd(id, body);
    }

    @PutMapping("/ads/{adId}")
    public Map<String, Object> updateAd(@PathVariable long id, @PathVariable long adId, @RequestBody Map<String, Object> body) {
        return community.updateAd(id, adId, body);
    }

    @PostMapping("/ads/{adId}/hidden")
    public Map<String, Object> hideAd(@PathVariable long id, @PathVariable long adId, @RequestBody Map<String, Object> body) {
        boolean hidden = Boolean.TRUE.equals(body.get("hidden"));
        community.setAdHidden(id, adId, hidden);
        return Map.of("hidden", hidden);
    }

    @DeleteMapping("/ads/{adId}")
    public Map<String, Object> deleteAd(@PathVariable long id, @PathVariable long adId) {
        community.deleteAd(id, adId);
        return Map.of("deleted", true);
    }

    @PostMapping(value = "/ads/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> upload(@PathVariable long id, @RequestPart("file") MultipartFile file) {
        return community.consoleUpload(id, file);
    }

    @GetMapping("/ads/{adId}/image")
    public ResponseEntity<byte[]> image(@PathVariable long id, @PathVariable long adId) {
        return AdImages.response(community.consoleImage(id, adId));
    }
}
