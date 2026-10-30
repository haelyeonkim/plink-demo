package com.plink.ticket.controller;

import com.plink.ticket.service.BoothManagerService;
import com.plink.ticket.service.CouponService;
import com.plink.ticket.service.GateAuthService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static com.plink.ticket.controller.BoothManagerController.ids;
import static com.plink.ticket.controller.BoothManagerController.string;

/**
 * The booth console from a booth terminal: the same routes, bodies and answers as a
 * booth manager's ({@link BoothManagerController}), authenticated the way every terminal
 * route is - the terminal's token, held to its bound device. Reading codes stays on the
 * terminal's own {@code /scan} and {@code /grant} routes.
 */
@RestController
@RequestMapping("/api/gates/{gateId}/booth")
public class BoothTerminalController {
    private final GateAuthService auth;
    private final BoothManagerService console;

    public BoothTerminalController(GateAuthService auth, BoothManagerService console) {
        this.auth = auth;
        this.console = console;
    }

    /** The terminal as the console's counter; refused unless it stands at a booth that still exists. */
    private CouponService.Counter counter(String gateId, String token, String device) {
        return console.terminal(auth.authenticate(gateId, token, device));
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard(@PathVariable String gateId,
            @RequestHeader(value = "X-Gate-Token", required = false) String token,
            @RequestHeader(value = "X-Gate-Device", required = false) String device) {
        return console.dashboard(counter(gateId, token, device));
    }

    @PostMapping("/offers")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> defineOffer(@PathVariable String gateId,
            @RequestHeader(value = "X-Gate-Token", required = false) String token,
            @RequestHeader(value = "X-Gate-Device", required = false) String device,
            @RequestBody Map<String, String> body) {
        return console.defineOffer(counter(gateId, token, device), body.get("title"), body.get("detail"));
    }

    @PutMapping("/offers/{offerId}")
    public Map<String, Object> updateOffer(@PathVariable String gateId, @PathVariable long offerId,
            @RequestHeader(value = "X-Gate-Token", required = false) String token,
            @RequestHeader(value = "X-Gate-Device", required = false) String device,
            @RequestBody Map<String, String> body) {
        return console.updateOffer(counter(gateId, token, device), offerId, body.get("title"), body.get("detail"));
    }

    @PostMapping("/offers/{offerId}/active")
    public Map<String, Object> setOfferActive(@PathVariable String gateId, @PathVariable long offerId,
            @RequestHeader(value = "X-Gate-Token", required = false) String token,
            @RequestHeader(value = "X-Gate-Device", required = false) String device,
            @RequestBody Map<String, Object> body) {
        return console.setOfferActive(counter(gateId, token, device), offerId,
            Boolean.TRUE.equals(body.get("active")));
    }

    @DeleteMapping("/offers/{offerId}")
    public Map<String, Object> deleteOffer(@PathVariable String gateId, @PathVariable long offerId,
            @RequestHeader(value = "X-Gate-Token", required = false) String token,
            @RequestHeader(value = "X-Gate-Device", required = false) String device) {
        return console.deleteOffer(counter(gateId, token, device), offerId);
    }

    @GetMapping("/tickets")
    public Map<String, Object> tickets(@PathVariable String gateId,
            @RequestHeader(value = "X-Gate-Token", required = false) String token,
            @RequestHeader(value = "X-Gate-Device", required = false) String device,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(defaultValue = "false") boolean inside,
            @RequestParam(required = false) Long offerId) {
        return console.tickets(counter(gateId, token, device), q, page, size, inside, offerId);
    }

    @PostMapping("/issue")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> issue(@PathVariable String gateId,
            @RequestHeader(value = "X-Gate-Token", required = false) String token,
            @RequestHeader(value = "X-Gate-Device", required = false) String device,
            @RequestBody Map<String, Object> body) {
        CouponService.Counter counter = counter(gateId, token, device);
        if (!(body.get("offerId") instanceof Number offerId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "발급할 쿠폰을 골라 주세요.");
        }
        int quantity = body.get("quantity") instanceof Number number ? number.intValue() : 1;
        return console.issue(counter, offerId.longValue(), string(body.get("target")), ids(body.get("ticketIds")),
            quantity);
    }

    @GetMapping("/coupons")
    public Map<String, Object> coupons(@PathVariable String gateId,
            @RequestHeader(value = "X-Gate-Token", required = false) String token,
            @RequestHeader(value = "X-Gate-Device", required = false) String device,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return console.coupons(counter(gateId, token, device), status, q, page, size);
    }

    @PostMapping("/coupons/status")
    public Map<String, Object> setStatuses(@PathVariable String gateId,
            @RequestHeader(value = "X-Gate-Token", required = false) String token,
            @RequestHeader(value = "X-Gate-Device", required = false) String device,
            @RequestBody Map<String, Object> body) {
        return console.setStatuses(counter(gateId, token, device), ids(body.get("ids")), string(body.get("status")));
    }
}
