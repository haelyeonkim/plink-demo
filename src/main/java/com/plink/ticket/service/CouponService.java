package com.plink.ticket.service;

import com.plink.ticket.model.Booth;
import com.plink.ticket.model.Coupon;
import com.plink.ticket.model.Gate;
import com.plink.ticket.model.Ticket;
import com.plink.ticket.repository.BoothRepository;
import com.plink.ticket.repository.CouponRepository;
import com.plink.ticket.repository.PresentationRepository;
import com.plink.ticket.repository.TicketRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Coupons: what a ticket can collect once it is inside, booth by booth.
 *
 * <p>Handing one over reads the same rotating code as the door does. That is deliberate:
 * the holder needs no second credential, the code is still single use, and a coupon
 * cannot be spent from a screenshot forwarded to a friend any more than an entry can.
 * What differs is the question asked of the code - not "may this person come in?" but
 * "does this person still have something waiting at this stand?".
 */
@Service
public class CouponService {
    private final CouponRepository coupons;
    private final BoothRepository booths;
    private final TicketRepository tickets;
    private final PresentationService presentations;
    private final PresentationRepository grants;
    private final com.plink.ticket.live.LiveEvents live;

    public CouponService(CouponRepository coupons, BoothRepository booths, TicketRepository tickets,
            PresentationService presentations, PresentationRepository grants,
            com.plink.ticket.live.LiveEvents live) {
        this.coupons = coupons;
        this.booths = booths;
        this.tickets = tickets;
        this.presentations = presentations;
        this.grants = grants;
        this.live = live;
    }

    /**
     * Gives an offer to tickets.
     *
     * <p>Already having it is not an error: an organiser adding the latecomers to a
     * batch should not have to work out who was in the last one.
     */
    @Transactional
    public Map<String, Object> issue(long sessionId, long boothId, String title, String detail,
            List<Long> ticketIds) {
        Booth booth = requireBooth(sessionId, boothId);
        String name = required(title, "쿠폰 이름을 입력해 주세요.");
        List<Long> targets = ticketIds == null || ticketIds.isEmpty()
            ? tickets.findBySession(sessionId).stream().filter(ticket -> !ticket.revoked())
                .map(ticket -> ticket.id).toList()
            : ticketIds;
        if (targets.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "쿠폰을 줄 입장권이 없어요.");
        }
        int given = 0, already = 0;
        for (long ticketId : targets) {
            Ticket ticket = tickets.findById(ticketId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "입장권을 찾을 수 없어요."));
            if (ticket.sessionId != sessionId) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "다른 행사의 입장권이에요.");
            }
            if (coupons.insert(sessionId, booth.id, ticketId, name, blankToNull(detail))) given++;
            else already++;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("boothId", booth.id);
        result.put("title", name);
        result.put("given", given);
        result.put("already", already);
        return result;
    }

    /**
     * The booth terminal's read: prove the code, then hand over one coupon.
     *
     * <p>The grant is consumed either way, exactly as at a door. A code that has been
     * shown to a stand is spent whether or not there was anything to give, so nobody can
     * try the same code around the room.
     */
    @Transactional
    public Map<String, Object> redeemByCode(Gate gate, String code) {
        if (gate.boothId == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이 단말에는 부스가 지정되어 있지 않아요.");
        }
        Booth booth = requireBooth(gate.sessionId, gate.boothId);
        PresentationService.Verified verified = presentations.verify(code);
        Ticket ticket = tickets.lockById(verified.grant.ticketId)
            .orElseThrow(() -> deny("입장권을 찾을 수 없어요."));
        if (ticket.sessionId != gate.sessionId) throw deny("다른 행사의 입장권이에요.");
        if (ticket.revoked()) throw deny("사용할 수 없는 입장권이에요.");
        presentations.consume(verified.grant, verified.counter);

        List<Coupon> live = coupons.lockLiveFor(ticket.id, booth.id);
        if (live.isEmpty()) {
            List<Coupon> all = coupons.findByTicketAndBooth(ticket.id, booth.id);
            Coupon spent = all.stream().filter(Coupon::redeemed).reduce((first, second) -> second).orElse(null);
            if (spent != null) {
                // Already collected: the terminal says when, so staff can settle it on
                // the spot instead of sending somebody to the desk.
                Map<String, Object> result = describe(spent, booth, ticket);
                result.put("outcome", "ALREADY");
                result.put("message", "이미 받아 간 쿠폰이에요.");
                announce(ticket, booth, spent, "ALREADY");
                return result;
            }
            throw deny(booth.name + "에서 쓸 수 있는 쿠폰이 없어요.");
        }
        Coupon coupon = live.get(0);
        Timestamp at = Timestamp.from(Instant.now());
        coupons.redeem(coupon.id, gate.id, at);
        coupon.status = "REDEEMED";
        coupon.redeemedAt = at;
        coupon.redeemedGate = gate.id;

        Map<String, Object> result = describe(coupon, booth, ticket);
        result.put("outcome", "REDEEMED");
        result.put("remaining", live.size() - 1);
        announce(ticket, booth, coupon, "REDEEMED");
        return result;
    }

    /** Tells the holder's screen, so the phone shows what was handed over. */
    private void announce(Ticket ticket, Booth booth, Coupon coupon, String outcome) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("outcome", outcome);
        message.put("booth", booth.name);
        message.put("title", coupon.title);
        message.put("at", Instant.now().toString());
        live.publishForTicket(ticket.sessionId, ticket.id, "COUPON", message);
    }

    private Map<String, Object> describe(Coupon coupon, Booth booth, Ticket ticket) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("couponId", coupon.id);
        result.put("booth", booth.name);
        result.put("title", coupon.title);
        result.put("ticketRef", ticket.ticketRef);
        result.put("seat", ticket.seat);
        result.put("redeemedAt", coupon.redeemedAt == null ? null : coupon.redeemedAt.toInstant().toString());
        return result;
    }

    /** What the holder's ticket shows: every offer they hold, and what became of it. */
    public List<Map<String, Object>> forTicket(long sessionId, long ticketId) {
        Map<Long, Booth> byId = new LinkedHashMap<>();
        booths.findBySession(sessionId).forEach(booth -> byId.put(booth.id, booth));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Coupon coupon : coupons.findByTicket(ticketId)) {
            if ("VOID".equals(coupon.status)) continue;
            Booth booth = byId.get(coupon.boothId);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("couponId", coupon.id);
            row.put("booth", booth == null ? null : booth.name);
            row.put("boothNote", booth == null ? null : booth.note);
            row.put("title", coupon.title);
            row.put("detail", coupon.detail);
            row.put("status", coupon.status);
            row.put("redeemedAt", coupon.redeemedAt == null ? null : coupon.redeemedAt.toInstant().toString());
            result.add(row);
        }
        return result;
    }

    public Booth requireBooth(long sessionId, long boothId) {
        Booth booth = booths.findById(boothId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "부스를 찾을 수 없어요."));
        if (booth.sessionId != sessionId) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "다른 행사의 부스예요.");
        }
        return booth;
    }

    private static String required(String value, String message) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        return text;
    }

    private static String blankToNull(String value) {
        String text = value == null ? "" : value.trim();
        return text.isEmpty() ? null : text;
    }

    private ResponseStatusException deny(String message) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }
}
