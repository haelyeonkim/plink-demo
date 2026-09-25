package com.plink.ticket.service;

import com.plink.ticket.model.Booth;
import com.plink.ticket.model.Coupon;
import com.plink.ticket.model.CouponOffer;
import com.plink.ticket.model.Gate;
import com.plink.ticket.model.Ticket;
import com.plink.ticket.repository.BoothRepository;
import com.plink.ticket.repository.CouponOfferRepository;
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
    private final CouponOfferRepository offers;
    private final BoothRepository booths;
    private final TicketRepository tickets;
    private final PresentationService presentations;
    private final PresentationRepository grants;
    private final com.plink.ticket.live.LiveEvents live;

    public CouponService(CouponRepository coupons, CouponOfferRepository offers, BoothRepository booths,
            TicketRepository tickets, PresentationService presentations, PresentationRepository grants,
            com.plink.ticket.live.LiveEvents live) {
        this.coupons = coupons;
        this.offers = offers;
        this.booths = booths;
        this.tickets = tickets;
        this.presentations = presentations;
        this.grants = grants;
        this.live = live;
    }

    /**
     * Adds something a booth can give. The name is what the holder and the counter will
     * both read, so it is unique within the booth: two offers with one name are one
     * tally split in half.
     */
    @Transactional
    public CouponOffer defineOffer(long sessionId, long boothId, String title, String detail) {
        Booth booth = requireBooth(sessionId, boothId);
        String name = required(title, "쿠폰 이름을 입력해 주세요.");
        if (name.length() > 80) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "쿠폰 이름은 80자까지예요.");
        }
        boolean taken = offers.findByBooth(booth.id).stream().anyMatch(offer -> offer.title.equals(name));
        if (taken) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                booth.name + "에 같은 이름의 쿠폰이 이미 있어요.");
        }
        long id = offers.insert(sessionId, booth.id, name, blankToNull(detail));
        return offers.findById(id).orElseThrow();
    }

    /**
     * Switches an offer on or off. Off stops it being given; coupons already given keep
     * working, because they were promised.
     */
    public CouponOffer setOfferActive(long offerId, boolean active) {
        CouponOffer offer = requireOffer(offerId);
        offers.setActive(offer.id, active);
        offer.active = active;
        return offer;
    }

    /** Removes an offer nobody has been given. One that has been given is switched off. */
    public void deleteOffer(long offerId) {
        CouponOffer offer = requireOffer(offerId);
        if (coupons.anyForOffer(offer.id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "이미 발급한 쿠폰이라 지울 수 없어요. 더 발급하지 않으려면 중지하세요.");
        }
        offers.delete(offer.id);
    }

    public CouponOffer requireOffer(long offerId) {
        return offers.findById(offerId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "쿠폰 종류를 찾을 수 없어요."));
    }

    /**
     * Gives one of a booth's offers to tickets.
     *
     * <p>Only an offer the booth has, and only while it is switched on. Already having it
     * is not an error: an organiser adding the latecomers to a batch should not have to
     * work out who was in the last one.
     */
    @Transactional
    public Map<String, Object> issue(long sessionId, long offerId, List<Long> ticketIds) {
        CouponOffer offer = requireOffer(offerId);
        if (offer.sessionId != sessionId) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "다른 행사의 쿠폰이에요.");
        }
        if (!offer.active) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "'" + offer.title + "' 쿠폰은 발급이 중지되어 있어요.");
        }
        Booth booth = requireBooth(sessionId, offer.boothId);
        List<Long> targets = ticketIds == null || ticketIds.isEmpty() ? tickets.liveIds(sessionId) : ticketIds;
        if (targets.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "쿠폰을 줄 입장권이 없어요.");
        }
        int given = 0, already = 0;
        for (long ticketId : targets) {
            if (ticketIds != null && !ticketIds.isEmpty()) {
                Ticket ticket = tickets.findById(ticketId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "입장권을 찾을 수 없어요."));
                if (ticket.sessionId != sessionId) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "다른 행사의 입장권이에요.");
                }
                if (ticket.revoked()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        ticket.ticketRef + "은(는) 비활성화된 입장권이에요.");
                }
            }
            if (coupons.insert(sessionId, booth.id, offer.id, ticketId, offer.title, offer.detail)) given++;
            else already++;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("boothId", booth.id);
        result.put("offerId", offer.id);
        result.put("title", offer.title);
        result.put("given", given);
        result.put("already", already);
        return result;
    }

    /**
     * Every booth's offers, with how many of each have been given, used, taken back and
     * are still waiting. The console's booth tabs and the terminals both read this.
     */
    public Map<Long, List<Map<String, Object>>> offersByBooth(long sessionId) {
        Map<Long, Map<String, Object>> tallies = new LinkedHashMap<>();
        for (Map<String, Object> row : offers.tallyBySession(sessionId)) {
            tallies.put(((Number) row.get("offer_id")).longValue(), row);
        }
        Map<Long, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (CouponOffer offer : offers.findBySession(sessionId)) {
            Map<String, Object> tally = tallies.getOrDefault(offer.id, Map.of());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("offerId", offer.id);
            row.put("title", offer.title);
            row.put("detail", offer.detail);
            row.put("active", offer.active);
            row.put("issued", count(tally.get("issued")));
            row.put("redeemed", count(tally.get("redeemed")));
            row.put("voided", count(tally.get("voided")));
            row.put("waiting", count(tally.get("waiting")));
            result.computeIfAbsent(offer.boothId, key -> new ArrayList<>()).add(row);
        }
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

    /**
     * What a booth terminal is standing there to hand out.
     *
     * <p>A stand's screen should say what it gives and how much of it is left, so that
     * somebody taking over the counter can read the tablet instead of asking. An offer
     * that has been switched off still shows while somebody is owed one.
     */
    public List<Map<String, Object>> boothOffers(long sessionId, long boothId) {
        return offersByBooth(sessionId).getOrDefault(boothId, List.of()).stream()
            .filter(offer -> Boolean.TRUE.equals(offer.get("active")) || count(offer.get("waiting")) > 0)
            .toList();
    }

    private static int count(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
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
