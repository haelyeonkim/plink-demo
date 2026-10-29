package com.plink.ticket.community;

import com.plink.ticket.model.Gate;
import com.plink.ticket.service.GateAuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/** A booth's own notices on the event's board, written at the booth's terminal. */
@RestController
@RequestMapping("/api/gates/{gateId}/ads")
public class BoothAdController {
    private final GateAuthService auth;
    private final CommunityService community;

    public BoothAdController(GateAuthService auth, CommunityService community) {
        this.auth = auth;
        this.community = community;
    }

    private Gate gate(String gateId, String token, String device) {
        return auth.authenticate(gateId, token, device);
    }

    @GetMapping
    public List<Map<String, Object>> list(@PathVariable String gateId,
            @RequestHeader(value = "X-Gate-Token", required = false) String token,
            @RequestHeader(value = "X-Gate-Device", required = false) String device) {
        return community.boothAds(gate(gateId, token, device));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@PathVariable String gateId,
            @RequestHeader(value = "X-Gate-Token", required = false) String token,
            @RequestHeader(value = "X-Gate-Device", required = false) String device,
            @RequestBody Map<String, Object> body) {
        return community.boothCreate(gate(gateId, token, device), body);
    }

    @PutMapping("/{adId}")
    public Map<String, Object> update(@PathVariable String gateId, @PathVariable long adId,
            @RequestHeader(value = "X-Gate-Token", required = false) String token,
            @RequestHeader(value = "X-Gate-Device", required = false) String device,
            @RequestBody Map<String, Object> body) {
        return community.boothUpdate(gate(gateId, token, device), adId, body);
    }

    /** Takes a notice down now; it stays in the booth's list as ended. */
    @PostMapping("/{adId}/end")
    public Map<String, Object> end(@PathVariable String gateId, @PathVariable long adId,
            @RequestHeader(value = "X-Gate-Token", required = false) String token,
            @RequestHeader(value = "X-Gate-Device", required = false) String device) {
        community.boothEnd(gate(gateId, token, device), adId);
        return Map.of("ended", true);
    }

    /** A photo for a notice the booth is writing; it is attached when the notice is saved. */
    @PostMapping(value = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> upload(@PathVariable String gateId,
            @RequestHeader(value = "X-Gate-Token", required = false) String token,
            @RequestHeader(value = "X-Gate-Device", required = false) String device,
            @RequestPart("file") MultipartFile file) {
        return community.boothUpload(gate(gateId, token, device), file);
    }

    @GetMapping("/{adId}/image")
    public ResponseEntity<byte[]> image(@PathVariable String gateId, @PathVariable long adId,
            @RequestHeader(value = "X-Gate-Token", required = false) String token,
            @RequestHeader(value = "X-Gate-Device", required = false) String device) {
        return AdImages.response(community.boothImage(gate(gateId, token, device), adId));
    }
}
