package com.plink.controller;

import tools.jackson.databind.JsonNode;
import com.plink.service.PasskeyService;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.*;

@RestController
@RequestMapping({ "/api/links/s/{code}/passkey", "/api/links/s/{slug}/{code}/passkey" })
public class PasskeyController {
    private final PasskeyService service;
    public PasskeyController(PasskeyService service) { this.service=service; }

    /**
     * A registration or sign-in ceremony. An unclaimed link makes a new passkey unless the
     * body says {"choice":"existing"}, which asks for one the visitor already has.
     */
    @PostMapping("/options")
    public Map<String,Object> options(@PathVariable String code, @RequestBody Map<String,String> body,
            HttpServletRequest request, HttpSession session) {
        return service.start(code, body.get("password"), body.get("choice"), request.getRemoteAddr(), session);
    }

    @PostMapping("/finish")
    public Map<String, Object> finish(@PathVariable String code, @RequestBody JsonNode credential,
            HttpSession session) {
        return service.finish(code, credential, session);
    }
}
