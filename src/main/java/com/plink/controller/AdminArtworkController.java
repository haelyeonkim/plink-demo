package com.plink.controller;

import com.plink.repository.ArtworkRepository;
import com.plink.repository.ContentRepository;
import com.plink.service.ContentImageService;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import static com.plink.service.ArtworkFields.artworkRows;

/** Cross-account library, guarded by SCOPE_ACCOUNTS in SecurityConfig. */
@RestController
@RequestMapping("/api/admin/artworks")
public class AdminArtworkController {
    private final ArtworkRepository artworks;
    private final ContentRepository contents;
    private final ContentImageService images;
    private final com.plink.service.ArtworkStatusService statuses;
    private final ObjectMapper mapper = new ObjectMapper();

    public AdminArtworkController(ArtworkRepository artworks, ContentRepository contents,
            ContentImageService images, com.plink.service.ArtworkStatusService statuses) {
        this.artworks = artworks; this.contents = contents; this.images = images;
        this.statuses = statuses;
    }

    @PatchMapping("/{id}/status")
    public java.util.Map<String, Object> changeStatus(@PathVariable long id,
            @RequestBody java.util.Map<String, Object> body) {
        if (!body.containsKey("saleStatus")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        statuses.change(id, body.get("saleStatus"));
        return java.util.Map.of("updated", true);
    }

    @GetMapping("/unlinked")
    public com.plink.service.ArtworkStatusService.PendingPage unlinked(@RequestParam(defaultValue = "0") int page) {
        if (page < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return statuses.pending(page);
    }

    public record Association(long contentId, int position, long artworkId) {}
    @GetMapping("/candidates/{contentId}")
    public ArtworkRepository.Page candidates(@PathVariable long contentId,
            @RequestParam(defaultValue = "") String search, @RequestParam(defaultValue = "0") int page) {
        if (page < 0 || search.length() > 200) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var content = contents.findById(contentId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return artworks.findAll(search.trim(), "all", page, 10, content.ownerSub);
    }
    @PostMapping("/associate")
    public java.util.Map<String, Object> associate(@RequestBody Association body) {
        statuses.connect(body.contentId(), body.position(), body.artworkId());
        return java.util.Map.of("connected", true);
    }

    @GetMapping
    @Transactional
    public ResponseEntity<ArtworkRepository.Page> list(@RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int pageSize) {
        if (page < 0 || pageSize < 1 || pageSize > 100 || search.length() > 200
                || !List.of("all", "unsold", "hold", "sold").contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "검색 조건을 확인해 주세요.");
        }
        // Include pre-library documents even if their owner has never opened the library.
        for (var content : contents.findUnindexed()) {
            var rows = artworkRows(mapper.readValue(content.body, Object.class));
            if (!rows.isEmpty()) artworks.replace(content.id, content.ownerSub, rows);
        }
        var result = artworks.findAll(search.trim(), status, page, pageSize);
        for (var row : result.items()) {
            String image = (String) row.get("image");
            if (image.startsWith(ContentImageService.PREFIX)) {
                row.put("image", "/api/admin/artworks/images/" + image.substring(ContentImageService.PREFIX.length()));
            }
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    @GetMapping("/images/{id}")
    public ResponseEntity<byte[]> image(@PathVariable String id) {
        var image = images.readArtworkForAdmin(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.type()))
            .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
            .header("Content-Disposition", "inline").body(image.bytes());
    }
}
