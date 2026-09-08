package com.plink.controller;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    @Value("${plink.auth.google-client-id}") private String clientId;
    @Value("${plink.auth.google-client-secret}") private String clientSecret;

    @GetMapping("/session")
    public Map<String, Object> session(@AuthenticationPrincipal OidcUser principal, CsrfToken csrf) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("googleEnabled", !clientId.trim().isEmpty() && !clientSecret.trim().isEmpty());
        result.put("csrfToken", csrf.getToken());
        result.put("csrfHeader", csrf.getHeaderName());
        Map<String, Object> user = null;
        if (principal != null) {
            user = new LinkedHashMap<>();
            user.put("name", principal.getFullName());
            user.put("email", principal.getEmail());
        }
        result.put("user", user);
        return result;
    }
}
