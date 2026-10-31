package com.plink.ticket.service;

import com.plink.ticket.config.TicketProperties;
import com.plink.ticket.model.Booth;
import com.plink.ticket.model.BoothManager;
import com.plink.ticket.model.CouponOffer;
import com.plink.ticket.model.EventSession;
import com.plink.ticket.model.Gate;
import com.plink.ticket.repository.BoothManagerRepository;
import com.plink.ticket.repository.BoothRepository;
import com.plink.ticket.repository.CouponRepository;
import com.plink.ticket.repository.GateRepository;
import com.plink.ticket.repository.TicketRepository;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.Locale;
import java.util.Map;

/**
 * Booth managers: people the organiser invites to run one booth from their own phone.
 *
 * <p>A manager gets the booth and nothing else. Every offer, coupon and code they touch
 * is checked against their own booth here, and what they do with coupons goes through
 * {@link CouponService}, so a phone at a stand follows exactly the rules a terminal at
 * the same stand does. An offer or coupon of another booth reads as not found rather
 * than forbidden: a manager has no business learning that it exists.
 *
 * <p>The invite link is a bearer secret only until it is taken up; after that it is an
 * address, and the passkey bound to it is what opens the booth (see
 * {@link BoothManagerPasskeyService}). Only a keyed hash of the token is stored.
 */
@Service
public class BoothManagerService {
    /** How often a manager's last visit is written down; more often only adds writes. */
    private static final Duration SEEN_EVERY = Duration.ofMinutes(1);
    private static final int RECENT = 10;

    private final BoothManagerRepository managers;
    private final BoothRepository booths;
    private final CouponService coupons;
    private final CouponRepository couponRows;
    private final TicketRepository tickets;
    private final TicketService ticketService;
    private final EmailSender mail;
    private final GateRepository gates;
    private final GateSetupService gateSetup;
    private final TicketProperties properties;
    private final String baseUrl;

    public BoothManagerService(BoothManagerRepository managers, BoothRepository booths, CouponService coupons,
            CouponRepository couponRows, TicketRepository tickets, TicketService ticketService, EmailSender mail,
            GateRepository gates, GateSetupService gateSetup,
            TicketProperties properties, @Value("${plink.auth.base-url}") String baseUrl) {
        this.managers = managers;
        this.gates = gates;
        this.gateSetup = gateSetup;
        this.booths = booths;
        this.coupons = coupons;
        this.couponRows = couponRows;
        this.tickets = tickets;
        this.ticketService = ticketService;
        this.mail = mail;
        this.properties = properties;
        this.baseUrl = baseUrl;
    }

    String tokenHash(String token) {
        return Secrets.hmacHex(properties.getTokenSecret(), "booth-manager-token|" + token);
    }

    String linkFor(String token) {
        return baseUrl + "/tickets/booth/" + token;
    }

    // ---- The link ---------------------------------------------------------------------

    /**
     * The manager a link belongs to. An unknown token and a deleted booth look alike; a
     * revoked one says so, because the person holding it was once let in and should know
     * why they no longer are.
     */
    public BoothManager resolve(String token) {
        if (token == null || token.length() < 32 || token.length() > 64) throw notFound();
        BoothManager manager = managers.findByTokenHash(tokenHash(token)).orElseThrow(this::notFound);
        if (manager.revoked()) {
            throw new ResponseStatusException(HttpStatus.GONE, "관리자 권한이 해지된 링크예요. 주최 측에 문의해 주세요.");
        }
        if (booths.findById(manager.boothId).isEmpty()) throw notFound();
        return manager;
    }

    /** What the link's page shows before anybody has signed in. Carries no secret. */
    public Map<String, Object> describe(BoothManager manager, boolean signedIn) {
        Booth booth = requireBooth(manager);
        EventSession session = ticketService.requireSession(manager.sessionId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("boothId", booth.id);
        result.put("boothName", booth.name);
        result.put("boothNote", booth.note);
        result.put("eventName", session.name);
        result.put("eventStartsAt", session.startsAt == null ? null : session.startsAt.toInstant().toString());
        result.put("venue", session.venue);
        result.put("emailMasked", TicketService.mask(manager.email));
        result.put("status", manager.claimed() ? "ACTIVE" : "INVITED");
        result.put("signedIn", signedIn);
        return result;
    }

    public void touch(BoothManager manager) {
        Instant now = Instant.now();
        managers.touch(manager.id, Timestamp.from(now), Timestamp.from(now.minus(SEEN_EVERY)));
    }

    // ---- The organiser's side -----------------------------------------------------------

    public List<Map<String, Object>> list(long boothId) {
        Booth booth = requireBooth(boothId);
        Map<String, Gate> terminals = new LinkedHashMap<>();
        gates.findBySession(booth.sessionId).forEach(gate -> terminals.put(gate.id, gate));
        List<Map<String, Object>> result = new ArrayList<>();
        for (BoothManager manager : managers.findByBooth(boothId)) {
            result.add(row(manager, manager.gateId == null ? null : terminals.get(manager.gateId)));
        }
        return result;
    }

    /** Who answers for each terminal of an event, invited or signed in, for the terminal cards. */
    public Map<String, List<Map<String, Object>>> byTerminal(long sessionId) {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        managers.assignedBySession(sessionId).forEach((gateId, list) -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (BoothManagerRepository.Assigned assigned : list) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("managerId", assigned.managerId());
                row.put("email", assigned.email());
                row.put("status", assigned.status());
                rows.add(row);
            }
            result.put(gateId, rows);
        });
        return result;
    }

    /**
     * Invites somebody to run a booth, and returns the link once - only its hash is kept,
     * so this is the one moment it can be copied without rotating it. An address that was
     * revoked before is invited afresh on the same row, which keeps its history.
     *
     * <p>The person answers for one of the booth's terminals - an existing one, or one made
     * here under the given name. The console always asks; an older caller naming none
     * still gets a manager who records as themselves.
     */
    @Transactional
    public Map<String, Object> invite(long boothId, String rawEmail, boolean notify, String gateId,
            String newTerminalLabel) {
        Booth booth = requireBooth(boothId);
        String email = EmailOtpService.normalize(rawEmail);
        // Checked before anything is written, so a refused terminal leaves no half invitation.
        Gate chosen = newTerminalLabel != null || gateId == null || gateId.isBlank() ? null
            : ownTerminal(booth, gateId);
        String token = Secrets.randomToken(32);
        BoothManager existing = managers.findByBoothAndEmail(booth.id, email).orElse(null);
        long id;
        if (existing == null) {
            try {
                id = managers.insert(booth.sessionId, booth.id, email, tokenHash(token));
            } catch (org.springframework.dao.DataIntegrityViolationException twin) {
                throw alreadyManager(email);
            }
        } else if (existing.revoked()) {
            id = existing.id;
            managers.reopen(id, tokenHash(token), Timestamp.from(Instant.now()));
        } else {
            throw alreadyManager(email);
        }
        // A new terminal is made once the invitation stands, so an address that is already
        // a manager does not leave a stray terminal to roll back.
        String terminal = newTerminalLabel != null ? newTerminal(booth, newTerminalLabel)
            : chosen == null ? null : chosen.id;
        managers.setGate(id, terminal);
        String link = linkFor(token);
        if (notify) sendInvite(booth, email, link, false);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("managerId", id);
        result.put("email", email);
        result.put("status", "INVITED");
        result.put("gateId", terminal);
        result.put("link", link);
        result.put("notified", notify);
        return result;
    }

    /**
     * A new link for a manager, and the old one dead at once. The invitation opens again,
     * so whoever follows the new link proves the mailbox and the passkey from the start:
     * a reissue is usually a phone that was lost, or a link that went somewhere it should not.
     */
    @Transactional
    public Map<String, Object> reissue(long managerId, boolean notify) {
        BoothManager manager = managers.lockById(managerId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "부스 관리자를 찾을 수 없어요."));
        if (manager.revoked()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "해지된 관리자예요. 다시 초대해 주세요.");
        }
        Booth booth = requireBooth(manager.boothId);
        String token = Secrets.randomToken(32);
        managers.reopen(manager.id, tokenHash(token), Timestamp.from(Instant.now()));
        String link = linkFor(token);
        if (notify) sendInvite(booth, manager.email, link, true);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("managerId", manager.id);
        result.put("status", "INVITED");
        result.put("link", link);
        result.put("notified", notify);
        return result;
    }

    /**
     * Shuts a manager out. The row stays so the console can say who used to run the
     * booth; the status is read on every request, so a phone already signed in loses the
     * booth on its next tap rather than when its sign-in runs out.
     */
    @Transactional
    public Map<String, Object> revoke(long managerId) {
        BoothManager manager = managers.findById(managerId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "부스 관리자를 찾을 수 없어요."));
        managers.revoke(manager.id);
        return Map.of("managerId", manager.id, "status", "REVOKED");
    }

    /**
     * Changes which terminal a manager answers for, or takes it away with null. What they
     * handed over before keeps the counter it was recorded under.
     */
    @Transactional
    public Map<String, Object> assign(long managerId, String gateId) {
        BoothManager manager = managers.lockById(managerId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "부스 관리자를 찾을 수 없어요."));
        if (manager.revoked()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "해지된 관리자예요. 다시 초대해 주세요.");
        }
        Booth booth = requireBooth(manager.boothId);
        Gate gate = gateId == null || gateId.isBlank() ? null : ownTerminal(booth, gateId);
        managers.setGate(manager.id, gate == null ? null : gate.id);
        manager.gateId = gate == null ? null : gate.id;
        return row(manager, gate);
    }

    /**
     * Makes this device the manager's terminal, as a correct setup code would: the person
     * the organiser put in charge of it, signed in with their passkey, is proof enough.
     * A device already holding it may ask again; another one has to be released first,
     * because a gate shared by two devices splits its record.
     */
    @Transactional
    public Map<String, Object> useAsTerminal(BoothManager manager, String deviceId) {
        if (!manager.claimed()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "패스키 등록을 마친 관리자만 단말을 연결할 수 있어요.");
        }
        Gate gate = assignedTerminal(manager);
        if (gate == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "담당 단말이 정해지지 않았어요. 주최 측에 문의해 주세요.");
        }
        Map<String, Object> result = new LinkedHashMap<>(gateSetup.handTo(gate, deviceId,
            "이 단말은 다른 기기에서 사용 중이에요. 주최 측에 연결 해제를 요청해 주세요."));
        result.remove("direction");
        return result;
    }

    /** Mailed once the invitation is committed, so a rollback never leaves a live-looking link in an inbox. */
    private void sendInvite(Booth booth, String email, String link, boolean again) {
        EventSession session = ticketService.requireSession(booth.sessionId);
        String subject = "[" + session.name + "] " + booth.name + (again ? " 부스 관리자 링크" : " 부스 관리자로 초대되었어요");
        String body = (again ? "새 부스 관리자 링크예요. 이전 링크는 더 이상 열리지 않아요.\n\n"
                : booth.name + " 부스의 쿠폰을 직접 관리할 수 있도록 초대되었어요.\n\n")
            + "아래 링크를 휴대폰에서 열고, 이메일 인증과 패스키 등록을 마치면 시작할 수 있어요.\n\n" + link
            + "\n\n링크는 이 주소의 본인만 쓸 수 있어요. 다른 사람에게 전달하지 마세요.";
        AfterCommit.run(() -> mail.send(email, subject, body));
    }

    private Map<String, Object> row(BoothManager manager, Gate terminal) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("managerId", manager.id);
        row.put("email", manager.email);
        row.put("status", manager.status);
        row.put("gateId", terminal == null ? null : terminal.id);
        row.put("gateLabel", terminal == null ? null : terminal.label);
        row.put("invitedAt", time(manager.invitedAt));
        row.put("claimedAt", time(manager.claimedAt));
        row.put("lastSeenAt", time(manager.lastSeenAt));
        return row;
    }

    /** How many people can open each booth of an event, for the console's booth list. */
    public Map<Long, Integer> openCounts(long sessionId) {
        return managers.countOpenBySession(sessionId);
    }

    // ---- The booth's console ------------------------------------------------------------------
    //
    // Everything below works against a counter - which event, which booth, and who is at
    // it - so a manager's phone and a terminal standing at the booth run the same console
    // under the same rules. The manager overloads only say which counter that is.

    /**
     * A booth terminal as the console's counter. Only a terminal that stands at a booth,
     * and at one that still exists, may run it: an admission door has no booth to run.
     */
    public CouponService.Counter terminal(Gate gate) {
        if (!gate.booth()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "부스 단말에서만 쓸 수 있어요.");
        }
        if (gate.boothId == null
                || booths.findById(gate.boothId).filter(b -> b.sessionId == gate.sessionId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "이 단말에 연결된 부스가 없어요.");
        }
        return CouponService.Counter.at(gate);
    }

    /** The booth at a glance, and which terminal this manager answers for, if any. */
    public Map<String, Object> dashboard(BoothManager manager) {
        Map<String, Object> result = new LinkedHashMap<>(dashboard(counter(manager)));
        Gate gate = assignedTerminal(manager);
        if (gate != null) {
            Map<String, Object> terminal = new LinkedHashMap<>();
            terminal.put("gateId", gate.id);
            terminal.put("label", gate.label);
            terminal.put("connected", gate.boundDevice != null);
            result.put("terminal", terminal);
        }
        return result;
    }

    public Map<String, Object> dashboard(CouponService.Counter counter) {
        Booth booth = requireBooth(counter);
        EventSession session = ticketService.requireSession(counter.sessionId());
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> boothRow = new LinkedHashMap<>();
        boothRow.put("boothId", booth.id);
        boothRow.put("name", booth.name);
        boothRow.put("note", booth.note);
        result.put("booth", boothRow);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("name", session.name);
        event.put("startsAt", session.startsAt == null ? null : session.startsAt.toInstant().toString());
        event.put("venue", session.venue);
        result.put("event", event);
        result.put("liveTickets", tickets.liveIds(counter.sessionId()).size());
        result.put("inside", managers.insideIds(counter.sessionId()).size());
        result.put("offers", offers(counter));
        List<Map<String, Object>> recent = new ArrayList<>();
        CouponRepository.CouponFilter filter = new CouponRepository.CouponFilter(booth.id, null, null, null);
        for (CouponRepository.Listed coupon : couponRows.search(counter.sessionId(), filter, RECENT, 0)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("couponId", coupon.id());
            row.put("title", coupon.title());
            row.put("ticketRef", coupon.ticketRef());
            row.put("status", coupon.status());
            row.put("at", time(coupon.redeemedAt() != null ? coupon.redeemedAt() : coupon.issuedAt()));
            recent.add(row);
        }
        result.put("recent", recent);
        return result;
    }

    /** The booth's offers with how each has gone, as the dashboard lists them. */
    private List<Map<String, Object>> offers(CouponService.Counter counter) {
        long boothId = boothOf(counter);
        Map<Long, Integer> holders = managers.holdersByOffer(boothId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> offer : coupons.offersByBooth(counter.sessionId())
                .getOrDefault(boothId, List.of())) {
            Map<String, Object> row = new LinkedHashMap<>(offer);
            row.put("holders", holders.getOrDefault((Long) offer.get("offerId"), 0));
            result.add(row);
        }
        return result;
    }

    private Map<String, Object> offerRow(CouponService.Counter counter, long offerId) {
        return offers(counter).stream().filter(row -> ((Long) row.get("offerId")) == offerId)
            .findFirst().orElseThrow(this::offerNotFound);
    }

    /** One of this booth's offers; another booth's reads as missing. */
    private CouponOffer ownOffer(CouponService.Counter counter, long offerId) {
        CouponOffer offer = coupons.requireOffer(offerId);
        if (offer.boothId != boothOf(counter) || offer.sessionId != counter.sessionId()) throw offerNotFound();
        return offer;
    }

    @Transactional
    public Map<String, Object> defineOffer(BoothManager manager, String title, String detail) {
        return defineOffer(counter(manager), title, detail);
    }

    @Transactional
    public Map<String, Object> defineOffer(CouponService.Counter counter, String title, String detail) {
        CouponOffer offer = coupons.defineOffer(counter.sessionId(), boothOf(counter), title, detail);
        return offerRow(counter, offer.id);
    }

    @Transactional
    public Map<String, Object> updateOffer(BoothManager manager, long offerId, String title, String detail) {
        return updateOffer(counter(manager), offerId, title, detail);
    }

    @Transactional
    public Map<String, Object> updateOffer(CouponService.Counter counter, long offerId, String title, String detail) {
        coupons.updateOffer(ownOffer(counter, offerId).id, title, detail);
        return offerRow(counter, offerId);
    }

    @Transactional
    public Map<String, Object> setOfferActive(BoothManager manager, long offerId, boolean active) {
        return setOfferActive(counter(manager), offerId, active);
    }

    @Transactional
    public Map<String, Object> setOfferActive(CouponService.Counter counter, long offerId, boolean active) {
        coupons.setOfferActive(ownOffer(counter, offerId).id, active);
        return offerRow(counter, offerId);
    }

    @Transactional
    public Map<String, Object> deleteOffer(BoothManager manager, long offerId) {
        return deleteOffer(counter(manager), offerId);
    }

    @Transactional
    public Map<String, Object> deleteOffer(CouponService.Counter counter, long offerId) {
        coupons.deleteOffer(ownOffer(counter, offerId).id);
        return Map.of("deleted", true, "offerId", offerId);
    }

    public Map<String, Object> tickets(BoothManager manager, String q, int page, int size, boolean insideOnly,
            Long offerId) {
        return tickets(counter(manager), q, page, size, insideOnly, offerId);
    }

    /** The event's usable tickets, a page at a time, with addresses masked. */
    public Map<String, Object> tickets(CouponService.Counter counter, String q, int page, int size,
            boolean insideOnly, Long offerId) {
        if (offerId != null) ownOffer(counter, offerId);
        int limit = Math.max(1, Math.min(size, 100));
        int current = Math.max(page, 0);
        String query = q == null || q.isBlank() ? null : q.strip();
        List<Map<String, Object>> items = new ArrayList<>();
        for (BoothManagerRepository.TicketRow ticket : managers.searchTickets(counter.sessionId(), query, insideOnly,
                offerId, limit, current * limit)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ticketId", ticket.ticketId());
            row.put("ticketRef", ticket.ticketRef());
            row.put("emailMasked", TicketService.mask(ticket.email()));
            row.put("inside", ticket.inside());
            row.put("held", ticket.held());
            items.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", managers.countTickets(counter.sessionId(), query, insideOnly));
        result.put("page", current);
        result.put("pageSize", limit);
        return result;
    }

    @Transactional
    public Map<String, Object> issue(BoothManager manager, long offerId, String rawTarget, List<Long> ticketIds,
            int quantity) {
        return issue(counter(manager), offerId, rawTarget, ticketIds, quantity);
    }

    /**
     * Gives one of this booth's offers to everybody, to whoever is inside right now, or to
     * the tickets picked from the list. The giving itself is {@link CouponService#issue}:
     * the same top-up, the same checks on each named ticket.
     */
    @Transactional
    public Map<String, Object> issue(CouponService.Counter counter, long offerId, String rawTarget,
            List<Long> ticketIds, int quantity) {
        CouponOffer offer = ownOffer(counter, offerId);
        String target = rawTarget == null ? "" : rawTarget.trim().toUpperCase(Locale.ROOT);
        List<Long> targets;
        switch (target) {
            case "ALL" -> targets = List.of();
            case "INSIDE" -> {
                targets = managers.insideIds(counter.sessionId());
                if (targets.isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지금 장내에 있는 입장권이 없어요.");
                }
            }
            case "PICKED" -> {
                if (ticketIds == null || ticketIds.isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "쿠폰을 줄 입장권을 골라 주세요.");
                }
                if (ticketIds.contains(null)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "입장권을 찾을 수 없어요.");
                }
                if (ticketIds.size() > 1000) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "한 번에 1,000장까지 고를 수 있어요.");
                }
                targets = ticketIds;
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "발급 대상을 골라 주세요.");
        }
        return coupons.issue(counter.sessionId(), offer.id, targets, quantity);
    }

    /**
     * The phone's camera at the counter. REDEEM reads a coupon's own code and hands it
     * over; GIVE reads the holder's ticket code and gives one of the chosen offer, or,
     * with none chosen, asks what to give the way a terminal does.
     */
    @Transactional
    public Map<String, Object> scan(BoothManager manager, String code, String rawAction, Long offerId) {
        CouponService.Counter counter = desk(manager);
        String action = rawAction == null ? "" : rawAction.trim().toUpperCase(Locale.ROOT);
        Map<String, Object> result;
        if ("REDEEM".equals(action)) {
            result = new LinkedHashMap<>(coupons.redeemByCode(counter, code));
        } else if ("GIVE".equals(action)) {
            CouponOffer offer = offerId == null ? null : ownOffer(counter, offerId);
            if (offer != null && !offer.active) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "'" + offer.title + "' 쿠폰은 발급이 중지되어 있어요.");
            }
            Map<String, Object> read = coupons.grantByCode(counter, code);
            // One offer chosen before the scan: the pick is answered at once, inside the
            // same transaction, so a refusal there spends the code no more than any other.
            result = new LinkedHashMap<>(offer == null ? read
                : coupons.grantPicked(counter, String.valueOf(read.get("pick")), Map.of(offer.id, 1)));
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지급인지 사용 처리인지 골라 주세요.");
        }
        result.put("message", message(result));
        return result;
    }

    /** The counter's answer after a GIVE read without an offer: how many of each to add. */
    @Transactional
    public Map<String, Object> confirmGive(BoothManager manager, String pick, Map<Long, Integer> wanted) {
        Map<String, Object> result = new LinkedHashMap<>(coupons.grantPicked(desk(manager), pick, wanted));
        result.put("message", message(result));
        return result;
    }

    /** The counter a manager stands at: their booth, and who they are, for what the booth records. */
    public static CouponService.Counter counter(BoothManager manager) {
        return CouponService.Counter.manager(manager.sessionId, manager.boothId, manager.id);
    }

    /**
     * The counter a manager hands coupons over at: their terminal, when they answer for
     * one, so the record names the terminal the organiser set up rather than a person;
     * otherwise themselves. The terminal lends its name and nothing else - the phone still
     * gives whatever the booth has switched on, not the terminal's own list - so the
     * counter carries its id as the key and no gate.
     */
    CouponService.Counter desk(BoothManager manager) {
        Gate gate = assignedTerminal(manager);
        return gate == null ? counter(manager)
            : CouponService.Counter.managerAt(manager.sessionId, manager.boothId, gate.id);
    }

    /**
     * The manager's terminal while it is still a terminal of their booth. One moved to
     * another booth since is not theirs to act as, and reads as none.
     */
    private Gate assignedTerminal(BoothManager manager) {
        if (manager.gateId == null) return null;
        return gates.findById(manager.gateId)
            .filter(gate -> gate.booth() && gate.boothId != null && gate.boothId == manager.boothId
                && gate.sessionId == manager.sessionId)
            .orElse(null);
    }

    /** One of this booth's terminals; anything else is not a terminal this booth can hand out. */
    private Gate ownTerminal(Booth booth, String gateId) {
        return gates.findById(gateId.trim())
            .filter(gate -> gate.booth() && gate.boothId != null && gate.boothId == booth.id
                && gate.sessionId == booth.sessionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "이 부스의 단말을 골라 주세요."));
    }

    /**
     * A new terminal for the booth, made as the console's 단말 추가 makes one: placed where
     * the booth says it is. The place column is short, so a long note is cut to fit.
     */
    private String newTerminal(Booth booth, String rawLabel) {
        String label = rawLabel.strip();
        if (label.isEmpty() || label.length() > 40) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "단말 이름은 1자부터 40자까지 정해 주세요.");
        }
        String zone = booth.note == null || booth.note.isBlank() ? booth.name : booth.note.strip();
        if (zone.length() > 32) zone = zone.substring(0, 32);
        Map<String, Object> made = gateSetup.register(booth.sessionId, null, label, zone, "BIDIRECTIONAL",
            "BOOTH", booth.id);
        return (String) made.get("gateId");
    }

    /** The booth a console counter runs; a counter without one has no console to run. */
    private static long boothOf(CouponService.Counter counter) {
        if (counter.boothId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "이 단말에 연결된 부스가 없어요.");
        }
        return counter.boothId();
    }

    /** What the big panel says, in words the person at the counter can read out. */
    private static String message(Map<String, Object> result) {
        Object title = result.get("title");
        return switch (String.valueOf(result.get("outcome"))) {
            case "REDEEMED" -> title + " 사용 처리했어요.";
            case "GRANTED" -> title + " 지급했어요.";
            case "ALREADY" -> "이미 사용한 쿠폰이에요.";
            case "CHOOSE" -> "줄 쿠폰을 골라 주세요.";
            default -> String.valueOf(result.getOrDefault("message", ""));
        };
    }

    public Map<String, Object> coupons(BoothManager manager, String status, String q, int page, int size) {
        return coupons(counter(manager), status, q, page, size);
    }

    /** This booth's coupons, a page at a time, with counts for every state. */
    public Map<String, Object> coupons(CouponService.Counter counter, String status, String q, int page, int size) {
        String state = status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);
        if ("ALL".equals(state)) state = null;
        if (state != null && !List.of("ISSUED", "REDEEMED", "VOID").contains(state)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "쿠폰 상태가 올바르지 않아요.");
        }
        int limit = Math.max(1, Math.min(size, 100));
        int current = Math.max(page, 0);
        String query = q == null || q.isBlank() ? null : q.strip();
        CouponRepository.CouponFilter filter = new CouponRepository.CouponFilter(boothOf(counter), null, state, query);
        List<Map<String, Object>> items = new ArrayList<>();
        for (CouponRepository.Listed coupon : couponRows.search(counter.sessionId(), filter, limit, current * limit)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("couponId", coupon.id());
            row.put("offerId", coupon.offerId());
            row.put("title", coupon.title());
            row.put("ticketRef", coupon.ticketRef());
            row.put("emailMasked", TicketService.mask(coupon.issuedToEmail()));
            row.put("status", coupon.status());
            row.put("issuedAt", time(coupon.issuedAt()));
            row.put("redeemedAt", time(coupon.redeemedAt()));
            // Where it was used: a terminal by name, a manager's phone, or the organiser by hand.
            row.put("redeemedBy", coupon.redeemedAt() == null ? null
                : coupon.redeemedAtGate() == null ? "운영자 처리" : coupon.redeemedAtGate());
            items.add(row);
        }
        Map<String, Long> byStatus = couponRows.countByStatus(counter.sessionId(), filter);
        Map<String, Object> counts = new LinkedHashMap<>();
        long issued = byStatus.getOrDefault("ISSUED", 0L);
        long redeemed = byStatus.getOrDefault("REDEEMED", 0L);
        long voided = byStatus.getOrDefault("VOID", 0L);
        counts.put("all", issued + redeemed + voided);
        counts.put("issued", issued);
        counts.put("redeemed", redeemed);
        counts.put("void", voided);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", couponRows.count(counter.sessionId(), filter));
        result.put("page", current);
        result.put("pageSize", limit);
        result.put("counts", counts);
        return result;
    }

    public Map<String, Object> setStatuses(BoothManager manager, List<Long> ids, String rawStatus) {
        return setStatuses(counter(manager), ids, rawStatus);
    }

    /**
     * Voids or restores this booth's coupons. Marking one used by hand stays with the
     * organiser: at the counter, a coupon is used by reading its code.
     */
    public Map<String, Object> setStatuses(CouponService.Counter counter, List<Long> ids, String rawStatus) {
        String status = rawStatus == null ? "" : rawStatus.trim().toUpperCase(Locale.ROOT);
        if (!List.of("VOID", "ISSUED").contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "쿠폰 상태가 올바르지 않아요.");
        }
        if (ids == null || ids.isEmpty() || ids.size() > 1000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "쿠폰을 1장부터 1,000장까지 골라 주세요.");
        }
        return coupons.setStatuses(counter.sessionId(), boothOf(counter), ids, status);
    }

    // ---- Helpers ----------------------------------------------------------------------------

    private Booth requireBooth(BoothManager manager) {
        return requireBooth(counter(manager));
    }

    private Booth requireBooth(CouponService.Counter counter) {
        long boothId = boothOf(counter);
        return booths.findById(boothId).filter(b -> b.sessionId == counter.sessionId())
            .orElseThrow(this::notFound);
    }

    private Booth requireBooth(long boothId) {
        return booths.findById(boothId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "부스를 찾을 수 없어요."));
    }

    private static ResponseStatusException alreadyManager(String email) {
        return new ResponseStatusException(HttpStatus.CONFLICT, email + "은(는) 이미 이 부스의 관리자예요.");
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "부스 관리자 링크를 찾을 수 없어요.");
    }

    private ResponseStatusException offerNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "쿠폰 종류를 찾을 수 없어요.");
    }

    private static String time(Timestamp at) {
        return at == null ? null : at.toInstant().toString();
    }
}
