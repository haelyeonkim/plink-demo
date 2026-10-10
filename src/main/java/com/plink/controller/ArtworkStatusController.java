package com.plink.controller;

import com.plink.service.ArtworkStatusEvents;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class ArtworkStatusController {
    private final ArtworkStatusEvents events;
    public ArtworkStatusController(ArtworkStatusEvents events) { this.events = events; }

    @GetMapping(value = {"/api/links/s/{code}/artwork-status/events",
        "/api/links/s/{slug}/{code}/artwork-status/events"}, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events(@PathVariable String code, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Accel-Buffering", "no")
            .body(events.subscribe(code, request.getSession(false)));
    }
}
