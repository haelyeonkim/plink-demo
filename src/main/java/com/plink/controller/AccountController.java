package com.plink.controller;

import com.plink.account.AdminAccount;
import com.plink.account.AdminAccountService;
import com.plink.account.AdminPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Account administration: who may open the link console, the ticket console, and this
 * screen. Reachable only with SCOPE_ACCOUNTS, which only an owner carries.
 */
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AdminAccountService accounts;
    private final com.plink.service.LinkService links;

    public AccountController(AdminAccountService accounts, com.plink.service.LinkService links) {
        this.accounts = accounts;
        this.links = links;
    }

    private static AdminPrincipal actor(Authentication authentication) {
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (principal instanceof AdminPrincipal admin) return admin;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
            "로컬 관리자 계정으로 로그인해야 계정을 관리할 수 있어요.");
    }

    private static Map<String, Object> describe(AdminAccount account) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", account.id);
        row.put("email", account.email);
        row.put("displayName", account.displayName);
        row.put("canLinks", account.canLinks);
        row.put("canTickets", account.canTickets);
        row.put("owner", account.owner);
        row.put("slug", account.slug);
        row.put("locked", account.locked());
        row.put("lastLoginAt", account.lastLoginAt == null ? null : account.lastLoginAt.toInstant().toString());
        row.put("createdAt", account.createdAt == null ? null : account.createdAt.toInstant().toString());
        return row;
    }

    private static boolean flag(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    /** Whether outbound mail is configured, and where it says it comes from. */
    @GetMapping("/mail")
    public Map<String, Object> mail() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configured", links.mailConfigured());
        result.put("from", links.mailFrom());
        return result;
    }

    /** Sends one message so the operator can see the setup work before it matters. */
    @PostMapping("/mail/test")
    public Map<String, Object> testMail(@RequestBody Map<String, String> body,
            Authentication authentication) {
        String to = body.get("to");
        if (to == null || to.isBlank()) to = actor(authentication).email;
        boolean sent = links.sendTestMail(to.trim());
        if (!sent) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                links.mailConfigured()
                    ? "메일 서버가 메시지를 거부했어요. 서버 로그를 확인해 주세요."
                    : "SMTP가 설정되지 않았어요. spring.mail.host 등을 설정해 주세요.");
        }
        return Map.of("sent", true, "to", to);
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AdminAccount account : accounts.list()) result.add(describe(account));
        return result;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        long id = accounts.create(
            body.get("email") == null ? null : body.get("email").toString(),
            body.get("password") == null ? null : body.get("password").toString(),
            body.get("displayName") == null ? null : body.get("displayName").toString(),
            flag(body.get("canLinks")), flag(body.get("canTickets")), flag(body.get("owner")));
        return Map.of("id", id);
    }

    @PutMapping("/{id}/permissions")
    public Map<String, Object> updatePermissions(@PathVariable long id,
            @RequestBody Map<String, Object> body, Authentication authentication) {
        accounts.updatePermissions(actor(authentication).id, id,
            flag(body.get("canLinks")), flag(body.get("canTickets")), flag(body.get("owner")));
        return Map.of("updated", true);
    }

    /** Renames the path segment this account's link addresses are published under. */
    @PutMapping("/{id}/slug")
    public Map<String, Object> updateSlug(@PathVariable long id, @RequestBody Map<String, String> body) {
        accounts.updateSlug(id, body.get("slug"));
        return Map.of("updated", true);
    }

    @PostMapping("/{id}/password")
    public Map<String, Object> resetPassword(@PathVariable long id, @RequestBody Map<String, String> body) {
        accounts.resetPassword(id, body.get("newPassword"));
        return Map.of("reset", true);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id, Authentication authentication) {
        accounts.delete(actor(authentication).id, id);
        return Map.of("deleted", true);
    }
}
