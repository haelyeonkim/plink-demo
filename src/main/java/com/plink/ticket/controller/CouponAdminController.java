package com.plink.ticket.controller;

import com.plink.ticket.model.CouponOffer;
import com.plink.ticket.repository.CouponRepository;
import com.plink.ticket.service.CouponService;
import com.plink.ticket.service.TicketService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The console's coupon table: rewording an offer, acting on many coupons at once, and a CSV of them. */
@RestController
@RequestMapping("/api/admin")
public class CouponAdminController {
    private static final DateTimeFormatter CSV_TIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));
    private static final int EXPORT_LIMIT = 50_000;

    private final TicketService tickets;
    private final CouponService coupons;
    private final CouponRepository couponRows;

    public CouponAdminController(TicketService tickets, CouponService coupons, CouponRepository couponRows) {
        this.tickets = tickets;
        this.coupons = coupons;
        this.couponRows = couponRows;
    }

    @PutMapping("/offers/{offerId}")
    public Map<String, Object> updateOffer(@PathVariable long offerId, @RequestBody Map<String, String> body) {
        CouponOffer offer = coupons.updateOffer(offerId, body.get("title"), body.get("detail"));
        return Map.of("offerId", offer.id, "title", offer.title);
    }

    /** One state for many coupons; see {@link CouponService#setStatuses}. */
    @PostMapping("/sessions/{id}/coupons/status")
    public Map<String, Object> setStatuses(@PathVariable long id, @RequestBody Map<String, Object> body) {
        tickets.requireSession(id);
        String status = String.valueOf(body.get("status")).toUpperCase(Locale.ROOT);
        if (!List.of("ISSUED", "REDEEMED", "VOID").contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "쿠폰 상태가 올바르지 않아요.");
        }
        if (!(body.get("couponIds") instanceof List<?> ids) || ids.isEmpty() || ids.size() > 1000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "쿠폰을 1장부터 1,000장까지 골라 주세요.");
        }
        List<Long> couponIds = ids.stream().map(raw -> raw instanceof Number n ? n.longValue() : null).toList();
        return coupons.setStatuses(id, couponIds, status);
    }

    /** The coupons the console is showing, every page of them, for a spreadsheet. */
    @GetMapping("/sessions/{id}/coupons/export")
    public ResponseEntity<byte[]> export(@PathVariable long id,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long boothId,
            @RequestParam(required = false) Long offerId,
            @RequestParam(required = false) String status) {
        boolean plain = tickets.requireSession(id).testEvent;
        String state = status == null || status.isBlank() ? null : status.toUpperCase(Locale.ROOT);
        if (state != null && !List.of("ISSUED", "REDEEMED", "VOID").contains(state)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "쿠폰 상태가 올바르지 않아요.");
        }
        String query = q == null || q.isBlank() ? null : q.strip();
        var filter = new CouponRepository.CouponFilter(boothId, offerId, state, query);
        StringBuilder csv = new StringBuilder("﻿"); // Excel reads Korean only with the mark.
        csv.append("쿠폰,부스,입장권,좌석,이메일,상태,받은 시각,사용 시각,사용한 곳\r\n");
        Map<String, String> label = new LinkedHashMap<>(Map.of("ISSUED", "미사용", "REDEEMED", "사용함", "VOID", "무효"));
        for (CouponRepository.Listed c : couponRows.search(id, filter, EXPORT_LIMIT, 0)) {
            List<String> cells = new ArrayList<>(List.of(
                text(c.title()), text(c.booth()), text(c.ticketRef()), text(c.seat()),
                text(plain ? c.issuedToEmail() : TicketService.mask(c.issuedToEmail())),
                label.getOrDefault(c.status(), c.status()), time(c.issuedAt()), time(c.redeemedAt()),
                c.redeemedAt() == null ? "" : c.redeemedAtGate() == null ? "운영자 처리" : c.redeemedAtGate()));
            csv.append(String.join(",", cells.stream().map(CouponAdminController::cell).toList())).append("\r\n");
        }
        return ResponseEntity.ok()
            .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"coupons-" + id + ".csv\"")
            .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String text(String value) { return value == null ? "" : value; }

    private static String time(Timestamp at) { return at == null ? "" : CSV_TIME.format(at.toInstant()); }

    /** Quoted when it must be, and never read by a spreadsheet as a formula. */
    private static String cell(String value) {
        String safe = !value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
        boolean quote = safe.indexOf('"') >= 0 || safe.indexOf(',') >= 0 || safe.indexOf('\r') >= 0 || safe.indexOf('\n') >= 0;
        return quote ? "\"" + safe.replace("\"", "\"\"") + "\"" : safe;
    }
}
