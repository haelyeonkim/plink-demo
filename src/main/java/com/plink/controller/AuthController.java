package com.plink.controller;

import com.plink.account.AdminAccountService;
import com.plink.account.AdminPrincipal;
import com.plink.account.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    @Value("${plink.auth.google-client-id}") private String clientId;
    @Value("${plink.auth.google-client-secret}") private String clientSecret;

    private final AdminAccountService accounts;

    public AuthController(AdminAccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping("/session")
    public Map<String, Object> session(Authentication authentication, CsrfToken csrf) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("googleEnabled", !clientId.trim().isEmpty() && !clientSecret.trim().isEmpty());
        result.put("csrfToken", csrf.getToken());
        result.put("csrfHeader", csrf.getHeaderName());
        result.put("user", CurrentUser.of(authentication).map(user -> {
            Map<String, Object> profile = new LinkedHashMap<>();
            profile.put("name", user.name);
            profile.put("email", user.email);
            // What the menu may show. The filter chain enforces the same thing; this
            // only spares the operator a link that would answer 403.
            profile.put("canLinks", has(authentication, AdminPrincipal.LINKS));
            profile.put("canTickets", has(authentication, AdminPrincipal.TICKETS));
            profile.put("canAccounts", has(authentication, AdminPrincipal.ACCOUNTS));
            return profile;
        }).orElse(null));
        return result;
    }

    private static boolean has(Authentication authentication, String authority) {
        return authentication != null && authentication.getAuthorities().stream()
            .anyMatch(granted -> authority.equals(granted.getAuthority()));
    }

    /** Local administrator sign-in, for deployments without a Google tenancy. */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body,
            HttpServletRequest request, HttpServletResponse response) {
        AdminPrincipal principal = accounts.signIn(body.get("email"), body.get("password"), request, response);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", principal.displayName);
        result.put("email", principal.email);
        result.put("canLinks", principal.canLinks);
        result.put("canTickets", principal.canTickets);
        result.put("canAccounts", principal.owner);
        return result;
    }

    @PostMapping("/password")
    public Map<String, Object> changePassword(@RequestBody Map<String, String> body,
            Authentication authentication) {
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (!(principal instanceof AdminPrincipal admin)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "비밀번호는 로컬 관리자 계정만 변경할 수 있어요.");
        }
        accounts.changePassword(admin.id, body.get("currentPassword"), body.get("newPassword"));
        return Map.of("changed", true);
    }
}
