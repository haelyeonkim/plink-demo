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
     * A registration or sign-in ceremony, or {"mode":"verify"} when the recipient's
     * address has no passkey yet and a code has been mailed to it first.
     */
    @PostMapping("/options")
    public Map<String,Object> options(@PathVariable String code, @RequestBody Map<String,String> body,
            HttpServletRequest request, HttpSession session) {
        return service.start(code, body.get("password"), body.get("contact"), request.getRemoteAddr(), session);
    }

    /** Checks the mailed code; the browser then asks for options again. */
    @PostMapping("/verify")
    public Map<String,Object> verify(@PathVariable String code, @RequestBody Map<String,String> body,
            HttpServletRequest request, HttpSession session) {
        service.verifyEmail(code, body.get("code"), request.getRemoteAddr(), session);
        return Collections.singletonMap("verified", true);
    }

    @PostMapping("/finish")
    public Map<String, Object> finish(@PathVariable String code, @RequestBody JsonNode credential,
            HttpSession session) {
        return service.finish(code, credential, session);
    }
}
