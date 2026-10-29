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

    @PutMapping("/chat/settings")
    public Map<String, Object> settings(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return community.saveSettings(id, body);
    }

    @GetMapping("/ads")
    public List<Map<String, Object>> ads(@PathVariable long id) {
        return community.consoleAds(id);
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
