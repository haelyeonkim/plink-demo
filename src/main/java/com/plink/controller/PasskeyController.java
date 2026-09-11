package com.plink.controller;

import tools.jackson.databind.JsonNode;
import com.plink.service.PasskeyService;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpSession;
import java.util.*;

@RestController
@RequestMapping("/api/links/s/{code}/passkey")
public class PasskeyController {
    private final PasskeyService service;
    public PasskeyController(PasskeyService service) { this.service=service; }
    @PostMapping("/options")
    public Map<String,Object> options(@PathVariable String code, @RequestBody Map<String,String> body, HttpSession session) {
        return service.start(code, body.get("password"), body.get("viewerName"), session);
    }
    @PostMapping("/finish")
    public Map<String,String> finish(@PathVariable String code, @RequestBody JsonNode credential, HttpSession session) {
        return Collections.singletonMap("originalUrl", service.finish(code, credential, session));
    }
}
