package com.plink.ticket.venue;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** The organiser's side of the venue tab. */
@RestController
@RequestMapping("/api/admin/sessions/{id}/venue")
public class VenueAdminController {
    private final VenueService venue;

    public VenueAdminController(VenueService venue) { this.venue = venue; }

    @GetMapping
    public Map<String, Object> list(@PathVariable long id) {
        return venue.console(id);
    }

    /** A new section of one kind, empty and hidden until the organiser fills and shows it. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return venue.create(id, body);
    }

    @PutMapping("/{sectionId}")
    public Map<String, Object> update(@PathVariable long id, @PathVariable long sectionId,
            @RequestBody Map<String, Object> body) {
        return venue.update(id, sectionId, body);
    }

    @PostMapping("/{sectionId}/visible")
    public Map<String, Object> visible(@PathVariable long id, @PathVariable long sectionId,
            @RequestBody Map<String, Object> body) {
        return venue.setVisible(id, sectionId, Boolean.TRUE.equals(body.get("visible")));
    }

    @PutMapping("/order")
    public Map<String, Object> order(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return venue.reorder(id, body.get("ids") instanceof List<?> ids ? ids : List.of());
    }

    @DeleteMapping("/{sectionId}")
    public Map<String, Object> delete(@PathVariable long id, @PathVariable long sectionId) {
        venue.delete(id, sectionId);
        return Map.of("deleted", true);
    }

    @PostMapping(value = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> upload(@PathVariable long id, @RequestPart("file") MultipartFile file) {
        return venue.upload(id, file);
    }

    @GetMapping("/images/{imageId}")
    public ResponseEntity<byte[]> image(@PathVariable long id, @PathVariable String imageId) {
        return image(venue.consoleImage(id, imageId));
    }

    /** A floor plan goes out the way a board photo does: kept by the browser that was allowed it. */
    static ResponseEntity<byte[]> image(VenueService.Download image) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.type()))
            .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
            .header("X-Content-Type-Options", "nosniff")
            .header("Content-Disposition", "inline").body(image.bytes());
    }
}
