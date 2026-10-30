package com.plink.ticket.controller;

import com.plink.ticket.model.BoothManager;
import com.plink.ticket.service.BoothManagerPasskeyService;
import com.plink.ticket.service.BoothManagerService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A booth manager's link: {@code /tickets/booth/{token}}.
 *
 * <p>The token finds the invitation; before it is taken up it is a bearer secret that
 * still needs the invited mailbox, and afterwards it opens nothing without the bound
 * passkey. Each request resolves the link afresh, so a revoked manager is refused on
 * the next tap whatever their browser remembers.
 */
@RestController
@RequestMapping("/api/booth/{token}")
public class BoothManagerController {
    private final BoothManagerService managers;
    private final BoothManagerPasskeyService passkeys;

    public BoothManagerController(BoothManagerService managers, BoothManagerPasskeyService passkeys) {
        this.managers = managers;
        this.passkeys = passkeys;
    }

    @GetMapping
    public Map<String, Object> view(@PathVariable String token, HttpServletRequest request) {
        BoothManager manager = managers.resolve(token);
        return managers.describe(manager, passkeys.signedIn(request.getSession(false), manager));
    }

    @PostMapping("/otp")
    public Map<String, Object> requestOtp(@PathVariable String token, @RequestBody Map<String, String> body) {
        passkeys.requestCode(managers.resolve(token), body.get("email"));
        return Collections.singletonMap("sent", true);
    }

    @PostMapping("/otp/verify")
    public Map<String, Object> verifyOtp(@PathVariable String token, @RequestBody Map<String, String> body,
            HttpSession session) {
        passkeys.verifyCode(managers.resolve(token), body.get("email"), body.get("code"), session);
        return Collections.singletonMap("verified", true);
    }

    @PostMapping("/passkey/options")
    public Map<String, Object> options(@PathVariable String token, HttpSession session) {
        return passkeys.start(managers.resolve(token), session);
    }

    @PostMapping("/passkey/finish")
    public Map<String, Object> finish(@PathVariable String token, @RequestBody JsonNode credential,
            HttpSession session) {
        return passkeys.finish(managers.resolve(token), credential, session);
    }

    // ---- Console: every route below needs this browser signed in to this booth. ----

    private BoothManager signedIn(String token, HttpSession session) {
        BoothManager manager = managers.resolve(token);
        passkeys.requireSignedIn(session, manager);
        return manager;
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard(@PathVariable String token, HttpSession session) {
        BoothManager manager = signedIn(token, session);
        managers.touch(manager);
        return managers.dashboard(manager);
    }

    @PostMapping("/offers")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> defineOffer(@PathVariable String token, @RequestBody Map<String, String> body,
            HttpSession session) {
        return managers.defineOffer(signedIn(token, session), body.get("title"), body.get("detail"));
    }

    @PutMapping("/offers/{offerId}")
    public Map<String, Object> updateOffer(@PathVariable String token, @PathVariable long offerId,
            @RequestBody Map<String, String> body, HttpSession session) {
        return managers.updateOffer(signedIn(token, session), offerId, body.get("title"), body.get("detail"));
    }

    @PostMapping("/offers/{offerId}/active")
    public Map<String, Object> setOfferActive(@PathVariable String token, @PathVariable long offerId,
            @RequestBody Map<String, Object> body, HttpSession session) {
        return managers.setOfferActive(signedIn(token, session), offerId, Boolean.TRUE.equals(body.get("active")));
    }

    @DeleteMapping("/offers/{offerId}")
    public Map<String, Object> deleteOffer(@PathVariable String token, @PathVariable long offerId,
            HttpSession session) {
        return managers.deleteOffer(signedIn(token, session), offerId);
    }

    @GetMapping("/tickets")
    public Map<String, Object> tickets(@PathVariable String token,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(defaultValue = "false") boolean inside,
            @RequestParam(required = false) Long offerId,
            HttpSession session) {
        return managers.tickets(signedIn(token, session), q, page, size, inside, offerId);
    }

    @PostMapping("/issue")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> issue(@PathVariable String token, @RequestBody Map<String, Object> body,
            HttpSession session) {
        BoothManager manager = signedIn(token, session);
        if (!(body.get("offerId") instanceof Number offerId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "발급할 쿠폰을 골라 주세요.");
        }
        int quantity = body.get("quantity") instanceof Number number ? number.intValue() : 1;
        return managers.issue(manager, offerId.longValue(), string(body.get("target")), ids(body.get("ticketIds")),
            quantity);
    }

    @PostMapping("/scan")
    public Map<String, Object> scan(@PathVariable String token, @RequestBody Map<String, Object> body,
            HttpSession session) {
        BoothManager manager = signedIn(token, session);
        Long offerId = body.get("offerId") instanceof Number number ? number.longValue() : null;
        return managers.scan(manager, string(body.get("code")), string(body.get("action")), offerId);
    }

    /** The answer to a GIVE read that came back asking what to give, as a terminal's confirm. */
    @PostMapping("/scan/confirm")
    public Map<String, Object> confirm(@PathVariable String token, @RequestBody Map<String, Object> body,
            HttpSession session) {
        BoothManager manager = signedIn(token, session);
        Map<Long, Integer> wanted = new LinkedHashMap<>();
        if (body.get("items") instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof Map<?, ?> row && row.get("offerId") instanceof Number offerId
                        && row.get("count") instanceof Number count) {
                    wanted.merge(offerId.longValue(), count.intValue(),
                        (a, b) -> (int) Math.min((long) a + b, Integer.MAX_VALUE));
                }
            }
        }
        return managers.confirmGive(manager, string(body.get("pick")), wanted);
    }

    @GetMapping("/coupons")
    public Map<String, Object> coupons(@PathVariable String token,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            HttpSession session) {
        return managers.coupons(signedIn(token, session), status, q, page, size);
    }

    @PostMapping("/coupons/status")
    public Map<String, Object> setStatuses(@PathVariable String token, @RequestBody Map<String, Object> body,
            HttpSession session) {
        BoothManager manager = signedIn(token, session);
        return managers.setStatuses(manager, ids(body.get("ids")), string(body.get("status")));
    }

    static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    static List<Long> ids(Object value) {
        List<Long> ids = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) ids.add(item instanceof Number n ? n.longValue() : null);
        }
        return ids;
    }
}
