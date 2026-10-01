package com.plink.controller;

import com.plink.account.CurrentUser;
import com.plink.service.ContentImageService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestController
public class ContentImageController {
    private final ContentImageService images;
    public ContentImageController(ContentImageService images) { this.images = images; }

    @PostMapping(value = "/api/contents/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> upload(@RequestPart("file") MultipartFile file, Authentication user) {
        String owner = CurrentUser.of(user).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED)).subject;
        return images.upload(owner, file);
    }
    @GetMapping("/api/content-images/{id}")
    public ResponseEntity<byte[]> read(@PathVariable String id, Authentication user, HttpServletRequest request) {
        var image = images.read(id, user, request.getSession(false));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.type()))
            .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
            .header("Content-Disposition", "inline").body(image.bytes());
    }
}
