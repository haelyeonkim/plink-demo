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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    /** The most of one offer a single ticket can be given. */
    public static final int MAX_PER_TICKET = 20;

    public Map<String, Object> issue(long sessionId, long offerId, List<Long> ticketIds) {
        return issue(sessionId, offerId, ticketIds, 1);
    }

    /**
     * Gives one of a booth's offers to tickets, {@code quantity} apiece.
     *
     * <p>Only an offer the booth has, and only while it is switched on. The quantity is
     * what each ticket should end up holding, counting what it already has, used or not:
     * an organiser adding the latecomers to a batch should not have to work out who was
     * in the last one, and running it twice must not hand out double.
     */
    @Transactional
    public Map<String, Object> issue(long sessionId, long offerId, List<Long> ticketIds, int quantity) {
        if (quantity < 1 || quantity > MAX_PER_TICKET) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "1인당 장수는 1장부터 " + MAX_PER_TICKET + "장까지예요.");
        }
        CouponOffer offer = offers.lockById(offerId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "쿠폰 종류를 찾을 수 없어요."));
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
        Map<Long, Integer> held = coupons.heldOf(offer.id);
        int given = 0, already = 0, reached = 0;
        for (long ticketId : new java.util.LinkedHashSet<>(targets)) {
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
            int missing = quantity - held.getOrDefault(ticketId, 0);
            if (missing <= 0) {
                already++;
                continue;
            }
            coupons.insert(sessionId, booth.id, offer.id, ticketId, offer.title, offer.detail, missing);
            given += missing;
            reached++;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("boothId", booth.id);
        result.put("offerId", offer.id);
        result.put("title", offer.title);
        result.put("quantity", quantity);
        // Coupons added, tickets that got some, and tickets that already had enough.
        result.put("given", given);
        result.put("tickets", reached);
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

    /** How long a terminal has to say what it is handing over, once a code is read. */
    static final Duration PICK_WINDOW = Duration.ofSeconds(90);

    /**
     * A read that is waiting for the counter to choose: which ticket, at which terminal,
     * and whether the choice is what to use up or what to give.
     */
    private record Pick(String kind, String gateId, long ticketId, Long boothId, Instant expires) {}

    private String newPick(String kind, Gate gate, long ticketId, Long boothId, Instant now) {
        picks.values().removeIf(pick -> pick.expires.isBefore(now));
        String id = Secrets.randomAlnum(24);
        picks.put(id, new Pick(kind, gate.id, ticketId, boothId, now.plus(PICK_WINDOW)));
        return id;
    }

    /** Takes a pick for its own terminal only; another terminal naming it does not spend it. */
    private Pick takePick(Gate gate, String pickId, String kind) {
        Pick pick = pickId == null ? null : picks.get(pickId);
        if (pick == null || !pick.gateId.equals(gate.id) || !pick.kind.equals(kind)
                || !picks.remove(pickId, pick) || pick.expires.isBefore(Instant.now())) {
            throw deny("선택 시간이 지났어요. 입장 QR을 다시 비춰 주세요.");
        }
        return pick;
    }

    private final Map<String, Pick> picks = new ConcurrentHashMap<>();

    /**
     * The booth terminal's read: prove the code, then hand over, or ask what to hand over.
     *
     * <p>The grant is consumed either way, exactly as at a door. A code that has been
     * shown to a stand is spent whether or not there was anything to give, so nobody can
     * try the same code around the room.
     *
     * <p>One coupon waiting is handed over on the spot; there is nothing to choose. More
     * than one and the counter decides - which kinds, and how many of each - so the
     * answer is a list and a short-lived pick that {@link #redeemPicked} spends.
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
                result.put("message", "이미 사용한 쿠폰이에요.");
                announce(ticket, booth, spent.title, "ALREADY");
                return result;
            }
            throw deny(booth.name + "에서 쓸 수 있는 쿠폰이 없어요.");
        }
        if (live.size() == 1) return handOver(gate, booth, ticket, live, Map.of(live.get(0).title, 1));

        Instant now = Instant.now();
        String id = newPick("REDEEM", gate, ticket.id, booth.id, now);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outcome", "CHOOSE");
        result.put("pick", id);
        result.put("expiresAt", now.plus(PICK_WINDOW).toString());
        result.put("booth", booth.name);
        result.put("ticketRef", ticket.ticketRef);
        result.put("seat", ticket.seat);
        result.put("choices", choices(live));
        return result;
    }

    /**
     * The counter's answer to a pick: how many of each kind to hand over now. The pick is
     * spent whatever is chosen, so a terminal cannot keep drawing on one read.
     */
    @Transactional
    public Map<String, Object> redeemPicked(Gate gate, String pickId, Map<String, Integer> wanted) {
        Pick pick = takePick(gate, pickId, "REDEEM");
        Booth booth = requireBooth(gate.sessionId, pick.boothId);
        Ticket ticket = tickets.lockById(pick.ticketId).orElseThrow(() -> deny("입장권을 찾을 수 없어요."));
        if (ticket.revoked()) throw deny("사용할 수 없는 입장권이에요.");
        Map<String, Integer> chosen = new LinkedHashMap<>();
        wanted.forEach((title, count) -> { if (count != null && count > 0) chosen.put(title, count); });
        if (chosen.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "사용할 쿠폰을 골라 주세요.");
        }
        List<Coupon> live = coupons.lockLiveFor(ticket.id, booth.id);
        for (Map.Entry<String, Integer> entry : chosen.entrySet()) {
            long held = live.stream().filter(coupon -> coupon.title.equals(entry.getKey())).count();
            if (entry.getValue() > held) {
                // Another terminal got there first, or the console took one back.
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "'" + entry.getKey() + "'은(는) " + held + "장만 남아 있어요.");
            }
        }
        return handOver(gate, booth, ticket, live, chosen);
    }

    /**
     * What this terminal may give, switched-on offers only, with the booth each belongs
     * to: the console decides the list, and a stopped offer drops out of it by itself.
     */
    public List<Map<String, Object>> gateGrants(Gate gate) {
        Map<Long, Booth> boothsById = new LinkedHashMap<>();
        booths.findBySession(gate.sessionId).forEach(booth -> boothsById.put(booth.id, booth));
        List<Map<String, Object>> result = new ArrayList<>();
        for (CouponOfferRepository.GateOffer grant : offers.grantsForGate(gate.id)) {
            CouponOffer offer = offers.findById(grant.offerId()).orElse(null);
            if (offer == null || !offer.active || offer.sessionId != gate.sessionId) continue;
            Booth booth = boothsById.get(offer.boothId);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("offerId", offer.id);
            row.put("title", offer.title);
            row.put("detail", offer.detail);
            row.put("booth", booth == null ? null : booth.name);
            row.put("auto", grant.autoOnEntry());
            result.add(row);
        }
        return result;
    }

    public List<CouponOfferRepository.GateOffer> grantsBySession(long sessionId) {
        return offers.grantsBySession(sessionId);
    }

    /**
     * Sets which offers a terminal may give. Giving by itself on entry is for a terminal
     * that admits people; a booth or an exit-only door has no entry to hang it on.
     */
    @Transactional
    public List<CouponOfferRepository.GateOffer> setGateGrants(Gate gate, Map<Long, Boolean> wanted) {
        boolean admits = !gate.booth() && gate.supports("IN");
        List<CouponOfferRepository.GateOffer> grants = new ArrayList<>();
        for (Map.Entry<Long, Boolean> entry : wanted.entrySet()) {
            CouponOffer offer = requireOffer(entry.getKey());
            if (offer.sessionId != gate.sessionId) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "다른 행사의 쿠폰이에요.");
            }
            grants.add(new CouponOfferRepository.GateOffer(gate.id, offer.id,
                admits && Boolean.TRUE.equals(entry.getValue())));
        }
        offers.replaceGrants(gate.id, grants);
        return grants;
    }

    /**
     * A terminal in giving mode reads a code: proves it, spends it, and asks the counter
     * what to give. Spending it keeps a screenshot from collecting twice.
     */
    @Transactional
    public Map<String, Object> grantByCode(Gate gate, String code) {
        List<Map<String, Object>> grantable = gateGrants(gate);
        if (grantable.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이 단말에서 줄 수 있는 쿠폰이 없어요.");
        }
        PresentationService.Verified verified = presentations.verify(code);
        Ticket ticket = tickets.lockById(verified.grant.ticketId)
            .orElseThrow(() -> deny("입장권을 찾을 수 없어요."));
        if (ticket.sessionId != gate.sessionId) throw deny("다른 행사의 입장권이에요.");
        if (ticket.revoked()) throw deny("사용할 수 없는 입장권이에요.");
        presentations.consume(verified.grant, verified.counter);

        List<Map<String, Object>> choices = new ArrayList<>();
        for (Map<String, Object> offer : grantable) {
            int held = coupons.heldBy((Long) offer.get("offerId"), ticket.id);
            Map<String, Object> row = new LinkedHashMap<>(offer);
            row.remove("auto");
            row.put("held", held);
            row.put("max", Math.max(0, MAX_PER_TICKET - held));
            choices.add(row);
        }
        Instant now = Instant.now();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outcome", "CHOOSE");
        result.put("pick", newPick("GRANT", gate, ticket.id, null, now));
        result.put("expiresAt", now.plus(PICK_WINDOW).toString());
        result.put("ticketRef", ticket.ticketRef);
        result.put("seat", ticket.seat);
        result.put("choices", choices);
        return result;
    }

    /** The counter's answer in giving mode: how many of each offer to add to the ticket. */
    @Transactional
    public Map<String, Object> grantPicked(Gate gate, String pickId, Map<Long, Integer> wanted) {
        Pick pick = takePick(gate, pickId, "GRANT");
        Ticket ticket = tickets.lockById(pick.ticketId).orElseThrow(() -> deny("입장권을 찾을 수 없어요."));
        if (ticket.revoked()) throw deny("사용할 수 없는 입장권이에요.");
        Map<Long, Integer> chosen = new LinkedHashMap<>();
        wanted.forEach((offerId, count) -> { if (count != null && count > 0) chosen.put(offerId, count); });
        if (chosen.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "부여할 쿠폰을 골라 주세요.");
        }
        java.util.Set<Long> allowed = new java.util.HashSet<>();
        gateGrants(gate).forEach(row -> allowed.add((Long) row.get("offerId")));
        List<Map<String, Object>> given = new ArrayList<>();
        int total = 0;
        for (Map.Entry<Long, Integer> entry : chosen.entrySet()) {
            if (!allowed.contains(entry.getKey())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "이 단말에서 줄 수 없는 쿠폰이에요.");
            }
            CouponOffer offer = offers.lockById(entry.getKey()).orElseThrow();
            int held = coupons.heldBy(offer.id, ticket.id);
            if (held + entry.getValue() > MAX_PER_TICKET) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "'" + offer.title + "'은(는) 한 사람에게 " + MAX_PER_TICKET + "장까지 줄 수 있어요.");
            }
            coupons.insert(gate.sessionId, offer.boothId, offer.id, ticket.id, offer.title, offer.detail,
                entry.getValue());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("title", offer.title);
            row.put("count", entry.getValue());
            given.add(row);
            total += entry.getValue();
        }
        String summary = summary(given);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outcome", "GRANTED");
        result.put("title", summary);
        result.put("granted", given);
        result.put("count", total);
        result.put("ticketRef", ticket.ticketRef);
        result.put("seat", ticket.seat);
        live.publishForTicket(ticket.sessionId, ticket.id, "COUPON", handover("GRANTED", null, summary));
        return result;
    }

    /**
     * What an admission gate gives by itself: each offer it is set to give on entry,
     * topped up to one, so coming back in does not collect a second.
     *
     * @return the titles given this time, empty when the ticket already had them
     */
    public List<String> autoGrantOnEntry(Gate gate, Ticket ticket) {
        List<String> given = new ArrayList<>();
        for (CouponOfferRepository.GateOffer grant : offers.grantsForGate(gate.id)) {
            if (!grant.autoOnEntry()) continue;
            CouponOffer offer = offers.lockById(grant.offerId()).orElse(null);
            if (offer == null || !offer.active || offer.sessionId != ticket.sessionId) continue;
            if (coupons.heldBy(offer.id, ticket.id) > 0) continue;
            coupons.insert(ticket.sessionId, offer.boothId, offer.id, ticket.id, offer.title, offer.detail, 1);
            given.add(offer.title);
        }
        return given;
    }

    /** Spends the chosen coupons, oldest of each kind first, and says what is left. */
    private Map<String, Object> handOver(Gate gate, Booth booth, Ticket ticket, List<Coupon> live,
            Map<String, Integer> chosen) {
        Timestamp at = Timestamp.from(Instant.now());
        List<Map<String, Object>> handed = new ArrayList<>();
        List<Coupon> left = new ArrayList<>(live);
        int total = 0;
        for (Map.Entry<String, Integer> entry : chosen.entrySet()) {
            int need = entry.getValue();
            for (java.util.Iterator<Coupon> it = left.iterator(); it.hasNext() && need > 0; ) {
                Coupon coupon = it.next();
                if (!coupon.title.equals(entry.getKey())) continue;
                coupons.redeem(coupon.id, gate.id, at);
                it.remove();
                need--;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("title", entry.getKey());
            row.put("count", entry.getValue());
            handed.add(row);
            total += entry.getValue();
        }
        String summary = summary(handed);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outcome", "REDEEMED");
        result.put("booth", booth.name);
        result.put("title", summary);
        result.put("handed", handed);
        result.put("count", total);
        result.put("ticketRef", ticket.ticketRef);
        result.put("seat", ticket.seat);
        result.put("redeemedAt", at.toInstant().toString());
        result.put("remaining", left.size());
        result.put("left", choices(left));
        announce(ticket, booth, summary, "REDEEMED");
        return result;
    }

    /** What is still waiting, kind by kind, in the order it was given. */
    private static List<Map<String, Object>> choices(List<Coupon> live) {
        Map<String, Map<String, Object>> byTitle = new LinkedHashMap<>();
        for (Coupon coupon : live) {
            Map<String, Object> row = byTitle.computeIfAbsent(coupon.title, title -> {
                Map<String, Object> fresh = new LinkedHashMap<>();
                fresh.put("title", title);
                fresh.put("detail", coupon.detail);
                fresh.put("available", 0);
                return fresh;
            });
            row.put("available", (Integer) row.get("available") + 1);
        }
        return new ArrayList<>(byTitle.values());
    }

    /** "웰컴 드링크" for one, "웰컴 드링크 · 리필 ×2" for more: what the counter says aloud. */
    private static String summary(List<Map<String, Object>> handed) {
        List<String> parts = new ArrayList<>();
        for (Map<String, Object> row : handed) {
            int count = (Integer) row.get("count");
            parts.add(count > 1 ? row.get("title") + " ×" + count : String.valueOf(row.get("title")));
        }
        return String.join(" · ", parts);
    }

    /** Tells the holder's screen, so the phone shows what was handed over. */
    private void announce(Ticket ticket, Booth booth, String title, String outcome) {
        live.publishForTicket(ticket.sessionId, ticket.id, "COUPON", handover(outcome, booth.name, title));
    }

    private static Map<String, Object> handover(String outcome, String booth, String title) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("outcome", outcome);
        message.put("booth", booth);
        message.put("title", title);
        message.put("at", Instant.now().toString());
        return message;
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
