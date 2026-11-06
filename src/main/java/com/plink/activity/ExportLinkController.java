package com.plink.activity;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** The organiser's side of an export: the link, its code, and the files. No sign-in. */
@RestController
@RequestMapping("/api/x/{token}")
public class ExportLinkController {
    private final ExportService exports;

    public ExportLinkController(ExportService exports) {
        this.exports = exports;
    }

    @GetMapping
    public Map<String, Object> open(@PathVariable String token, HttpServletRequest request) {
        return exports.open(token, request);
    }

    @PostMapping("/code")
    public Map<String, Object> code(@PathVariable String token, HttpServletRequest request) {
        return exports.sendCode(token, request);
    }

    @PostMapping("/verify")
    public Map<String, Object> verify(@PathVariable String token, @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        return exports.verify(token, body == null || body.get("code") == null ? null : String.valueOf(body.get("code")), request);
    }

    @GetMapping("/files/{name}")
    public ResponseEntity<byte[]> file(@PathVariable String token, @PathVariable String name, HttpServletRequest request) {
        return send(exports.download(token, name, request));
    }

    @GetMapping("/zip")
    public ResponseEntity<byte[]> zip(@PathVariable String token, HttpServletRequest request) {
        return send(exports.download(token, null, request));
    }

    private static ResponseEntity<byte[]> send(ExportService.Download file) {
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(file.contentType()))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(file.name(), StandardCharsets.UTF_8).build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(file.bytes());
    }
}
