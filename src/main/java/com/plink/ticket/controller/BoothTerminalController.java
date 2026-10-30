package com.plink.ticket.controller;

import com.plink.ticket.community.AdImages;
import com.plink.ticket.community.CommunityService;
import com.plink.ticket.service.BoothManagerService;
import com.plink.ticket.service.CouponService;
import com.plink.ticket.service.GateAuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
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
    private final CommunityService community;

    public BoothTerminalController(GateAuthService auth, BoothManagerService console, CommunityService community) {
        this.auth = auth;
        this.console = console;
        this.community = community;
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

    // ---- The booth's notices on the event's board; each waits for the organiser. ----

    @GetMapping("/ads")
    public List<Map<String, Object>> ads(@PathVariable String gateId,
            @RequestHeader(value = "X-Gate-Token", required = false) String token,
            @RequestHeader(value = "X-Gate-Device", required = false) String device) {
        return community.boothAds(counter(gateId, token, device));
    }

    @PostMapping("/ads")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createAd(@PathVariable String gateId,
            @RequestHeader(value = "X-Gate-Token", required = false) String token,
            @RequestHeader(value = "X-Gate-Device", required = false) String device,
            @RequestBody Map<String, Object> body) {
        return community.boothCreate(counter(gateId, token, device), body);
    }

    @PutMapping("/ads/{adId}")
    public Map<String, Object> updateAd(@PathVariable String gateId, @PathVariable long adId,
            @RequestHeader(value = "X-Gate-Token", required = false) String token,
            @RequestHeader(value = "X-Gate-Device", required = false) String device,
            @RequestBody Map<String, Object> body) {
        return community.boothUpdate(counter(gateId, token, device), adId, body);
    }

    @DeleteMapping("/ads/{adId}")
    public Map<String, Object> deleteAd(@PathVariable String gateId, @PathVariable long adId,
            @RequestHeader(value = "X-Gate-Token", required = false) String token,
            @RequestHeader(value = "X-Gate-Device", required = false) String device) {
        community.boothDelete(counter(gateId, token, device), adId);
        return Map.of("deleted", true);
    }

    @PostMapping("/ads/{adId}/end")
    public Map<String, Object> endAd(@PathVariable String gateId, @PathVariable long adId,
            @RequestHeader(value = "X-Gate-Token", required = false) String token,
            @RequestHeader(value = "X-Gate-Device", required = false) String device) {
        community.boothEnd(counter(gateId, token, device), adId);
        return Map.of("ended", true);
    }

    @PostMapping(value = "/ads/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> uploadAdImage(@PathVariable String gateId,
            @RequestHeader(value = "X-Gate-Token", required = false) String token,
            @RequestHeader(value = "X-Gate-Device", required = false) String device,
            @RequestPart("file") MultipartFile file) {
        return community.boothUpload(counter(gateId, token, device), file);
    }

    @GetMapping("/ads/{adId}/image")
    public ResponseEntity<byte[]> adImage(@PathVariable String gateId, @PathVariable long adId,
            @RequestHeader(value = "X-Gate-Token", required = false) String token,
            @RequestHeader(value = "X-Gate-Device", required = false) String device) {
        return AdImages.response(community.boothImage(counter(gateId, token, device), adId));
    }
}
