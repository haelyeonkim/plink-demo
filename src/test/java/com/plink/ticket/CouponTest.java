package com.plink.ticket;

import com.plink.ticket.model.Coupon;
import com.plink.ticket.model.Gate;
import com.plink.ticket.model.Ticket;
import com.plink.ticket.repository.BoothRepository;
import com.plink.ticket.repository.CouponRepository;
import com.plink.ticket.repository.EventSessionRepository;
import com.plink.ticket.repository.GateRepository;
import com.plink.ticket.repository.HolderRepository;
import com.plink.ticket.repository.TicketRepository;
import com.plink.ticket.service.CouponService;
import com.plink.ticket.service.GateAuthService;
import com.plink.ticket.service.PresentationService;
import com.plink.ticket.service.Secrets;
import com.plink.ticket.service.TicketService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coupons are handed over by reading the ticket's own rotating code, so the rules that
 * protect a door protect a stand: one use per code, nothing spendable from a screenshot,
 * and one offer per person per booth however many times they come back.
 */
// Isolated from ./.env: the suite must not depend on whichever origin, secret or
// face service a developer happens to have configured locally.
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:coupontest;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.config.import=", "plink.admin.email=", "plink.admin.password="})
@Import(RecordingEmail.class)
class CouponTest {

    @Autowired CouponService coupons;
    @Autowired CouponRepository couponRepository;
    @Autowired BoothRepository booths;
    @Autowired com.plink.ticket.repository.CouponOfferRepository offers;
    @Autowired TicketService tickets;
    @Autowired TicketRepository ticketRepository;
    @Autowired EventSessionRepository sessions;
    @Autowired HolderRepository holders;
    @Autowired GateRepository gates;
    @Autowired GateAuthService gateAuth;
    @Autowired PresentationService presentations;
    @Autowired com.plink.ticket.controller.TicketAdminController adminController;
    @Autowired com.plink.ticket.controller.CouponAdminController couponAdmin;
    @Autowired com.plink.ticket.service.AdmissionService admissions;
    @Autowired com.plink.ticket.repository.TicketFieldRepository fields;

    private long sessionId;

    private long newSession() {
        sessionId = sessions.insert("쿠폰 이벤트 " + Secrets.randomAlnum(6), "메인홀",
            Timestamp.from(Instant.now().plus(2, ChronoUnit.HOURS)), null);
        return sessionId;
    }

    private Ticket boundTicket(String email, String seat) {
        long id = ((Number) tickets.issue(sessionId, email, seat, null).get("ticketId")).longValue();
        long holderId = holders.findByEmail(email).map(h -> h.id)
            .orElseGet(() -> holders.create(email, tickets.userHandleFor(email)));
        ticketRepository.bind(id, holderId, email);
        return ticketRepository.findById(id).orElseThrow();
    }

    /** Gives a booth's offer, defining it first the way the console would. */
    private Map<String, Object> give(long boothId, String title, String detail, List<Long> ticketIds) {
        long offerId = offers.findByBooth(boothId).stream().filter(offer -> offer.title.equals(title))
            .map(offer -> offer.id).findFirst()
            .orElseGet(() -> coupons.defineOffer(sessionId, boothId, title, detail).id);
        return coupons.issue(sessionId, offerId, ticketIds);
    }

    private Gate boothGate(long boothId) {
        String id = "b" + Secrets.randomAlnum(10);
        gates.insert(id, sessionId, "부스 단말", "메인홀", "BIDIRECTIONAL",
            gateAuth.hash("t-" + id), null, null, "BOOTH", boothId);
        return gates.findById(id).orElseThrow();
    }

    /** The code the holder's phone would be showing. */
    private String code(Ticket ticket) {
        return TicketCodes.code(presentations.issue(ticket, "IN", true), 1);
    }

    /** One coupon's own code, as the holder opens it from the coupon tab. */
    private String couponCode(Ticket ticket, Coupon coupon) {
        return TicketCodes.code(coupons.openCode(ticket, coupon.id), 1);
    }

    private Coupon waiting(Ticket ticket, String title) {
        return couponRepository.findByTicket(ticket.id).stream()
            .filter(c -> c.title.equals(title) && "ISSUED".equals(c.status)).findFirst().orElseThrow();
    }

    /** "Everybody whose 등급 is VIP" is found on the server, and live tickets only. */
    @Test void couponsCanGoToTicketsMatchingAField() {
        newSession();
        fields.insert(sessionId, "등급", "TIER", "VIP\n일반");
        long vip = ((Number) tickets.issue(sessionId, "vip@example.com", null, "VIP").get("ticketId")).longValue();
        long offVip = ((Number) tickets.issue(sessionId, "offvip@example.com", null, "VIP").get("ticketId")).longValue();
        tickets.issue(sessionId, "plain@example.com", null, "일반");
        tickets.setRevoked(offVip, true);
        long booth = booths.insert(sessionId, "라운지", null);
        long offerId = coupons.defineOffer(sessionId, booth, "디저트", null).id;
        Map<String, Object> given = adminController.issueCoupons(sessionId,
            Map.of("offerId", offerId, "match", Map.of("field", "등급", "value", "VIP")));
        assertEquals(1, ((Number) given.get("tickets")).intValue());
        assertEquals(1, couponRepository.findByTicket(vip).size());
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> adminController.issueCoupons(sessionId,
            Map.of("offerId", offerId, "match", Map.of("field", "등급", "value", "없는 등급"))));
    }

    /** A booth gives only what it has been set up to give, and only while that is on. */
    @Test void onlyADefinedOfferThatIsSwitchedOnCanBeGiven() {
        newSession();
        Ticket ticket = boundTicket("holder@example.com", "A-1");
        long booth = booths.insert(sessionId, "커피 스탠드", null);
        com.plink.ticket.model.CouponOffer coffee = coupons.defineOffer(sessionId, booth, "웰컴 커피", "1인 1잔");

        ResponseStatusException twin = assertThrows(ResponseStatusException.class,
            () -> coupons.defineOffer(sessionId, booth, "웰컴 커피", null));
        assertEquals(409, twin.getStatusCode().value(), "한 부스에 같은 이름은 하나");

        coupons.setOfferActive(coffee.id, false);
        ResponseStatusException off = assertThrows(ResponseStatusException.class,
            () -> coupons.issue(sessionId, coffee.id, List.of(ticket.id)));
        assertEquals(409, off.getStatusCode().value());
        assertTrue(String.valueOf(off.getReason()).contains("중지"), off.getReason());

        coupons.setOfferActive(coffee.id, true);
        assertEquals(1, coupons.issue(sessionId, coffee.id, List.of(ticket.id)).get("given"));
        assertEquals("1인 1잔", coupons.forTicket(sessionId, ticket.id).get(0).get("detail"),
            "쿠폰은 정의된 문구를 그대로 가져갑니다");

        // Another event's offer is not this event's to give.
        long mine = sessionId;
        newSession();
        long elsewhere = booths.insert(sessionId, "굿즈 부스", null);
        long foreign = coupons.defineOffer(sessionId, elsewhere, "스티커", null).id;
        assertEquals(400, assertThrows(ResponseStatusException.class,
            () -> coupons.issue(mine, foreign, List.of(ticket.id))).getStatusCode().value());
    }

    /**
     * Once given, an offer is switched off rather than deleted, and what was given keeps
     * working: it was promised. The stand's screen keeps showing it while anyone is owed.
     */
    @Test void aGivenOfferIsSwitchedOffNotDeleted() {
        newSession();
        Ticket ticket = boundTicket("holder@example.com", "A-1");
        long booth = booths.insert(sessionId, "커피 스탠드", null);
        long coffee = coupons.defineOffer(sessionId, booth, "웰컴 커피", null).id;
        long unused = coupons.defineOffer(sessionId, booth, "리필", null).id;
        coupons.issue(sessionId, coffee, List.of(ticket.id));

        assertEquals(409, assertThrows(ResponseStatusException.class,
            () -> coupons.deleteOffer(coffee)).getStatusCode().value());
        coupons.deleteOffer(unused);
        assertTrue(offers.findById(unused).isEmpty(), "아무도 받지 않은 쿠폰은 지울 수 있어요");

        coupons.setOfferActive(coffee, false);
        assertEquals(1, coupons.boothOffers(sessionId, booth).size(), "받을 사람이 남아 있으면 단말에 보입니다");
        assertEquals("REDEEMED", coupons.redeemByCode(boothGate(booth), couponCode(ticket, waiting(ticket, "웰컴 커피"))).get("outcome"),
            "중지해도 이미 준 쿠폰은 쓸 수 있어요");
        assertEquals(0, coupons.boothOffers(sessionId, booth).size(), "다 건넨 뒤에는 단말에서 빠집니다");
    }

    /** A revoked ticket is not somebody who is coming to collect anything. */
    @Test void aRevokedTicketIsNotGivenAnything() {
        newSession();
        Ticket ticket = boundTicket("holder@example.com", "A-1");
        ticketRepository.updateStatus(ticket.id, "REVOKED");
        long booth = booths.insert(sessionId, "커피 스탠드", null);
        long coffee = coupons.defineOffer(sessionId, booth, "웰컴 커피", null).id;
        assertEquals(400, assertThrows(ResponseStatusException.class,
            () -> coupons.issue(sessionId, coffee, List.of(ticket.id))).getStatusCode().value());
    }

    /** The coupon table comes a page at a time, and a booth's tab is a filter on it. */
    @Test void theCouponListComesAPageAtATime() {
        newSession();
        long coffee = booths.insert(sessionId, "커피 스탠드", null);
        long merch = booths.insert(sessionId, "굿즈 부스", null);
        for (int i = 1; i <= 5; i++) boundTicket("p" + i + "@example.com", "C-" + i);
        give(coffee, "웰컴 커피", null, List.of());
        give(merch, "스티커", null, List.of());

        Map<String, Object> first = adminController.listCoupons(sessionId, 0, 4, null, null, null, null);
        assertEquals(10L, first.get("total"));
        assertEquals(4, ((List<?>) first.get("items")).size());
        assertEquals(2, ((List<?>) adminController.listCoupons(sessionId, 2, 4, null, null, null, null)
            .get("items")).size(), "마지막 쪽에는 남은 것만");

        assertEquals(5L, adminController.listCoupons(sessionId, 0, 50, null, merch, null, null).get("total"));
        assertEquals(1L, adminController.listCoupons(sessionId, 0, 50, "c-3", coffee, null, null).get("total"),
            "좌석으로 찾기");
        assertEquals(0L, adminController.listCoupons(sessionId, 0, 50, null, null, null, "REDEEMED").get("total"));
    }

    @Test void theConsoleActsOnManyCouponsAndOnlyWhereTheMoveMakesSense() {
        newSession();
        long booth = booths.insert(sessionId, "커피", null);
        Ticket a = boundTicket("many-a@example.com", "A-1");
        Ticket b = boundTicket("many-b@example.com", "A-2");
        give(booth, "웰컴 드링크", "1잔", List.of(a.id, b.id));
        List<Long> ids = couponRepository.findByTicket(a.id).stream().map(c -> c.id).toList();
        List<Long> both = new java.util.ArrayList<>(ids);
        both.addAll(couponRepository.findByTicket(b.id).stream().map(c -> c.id).toList());

        Map<String, Object> used = couponAdmin.setStatuses(sessionId, Map.of("couponIds", ids, "status", "REDEEMED"));
        assertEquals(1, used.get("changed"));
        Coupon spent = couponRepository.findById(ids.get(0)).orElseThrow();
        assertEquals("REDEEMED", spent.status);
        assertNull(spent.redeemedGate, "marked by the organiser, not read at a terminal");

        Map<String, Object> voided = couponAdmin.setStatuses(sessionId, Map.of("couponIds", both, "status", "VOID"));
        assertEquals(2, voided.get("changed"));
        Map<String, Object> again = couponAdmin.setStatuses(sessionId, Map.of("couponIds", both, "status", "REDEEMED"));
        assertEquals(0, again.get("changed"), "a void coupon is not marked used");
        assertEquals(2, again.get("skipped"));

        // Another event's coupons are not touched from here.
        long other = sessionId;
        newSession();
        assertEquals(0, couponAdmin.setStatuses(sessionId, Map.of("couponIds", both, "status", "ISSUED")).get("changed"));
        assertEquals(2, couponAdmin.setStatuses(other, Map.of("couponIds", both, "status", "ISSUED")).get("changed"));

        Map<?, ?> counts = (Map<?, ?>) adminController.listCoupons(other, 0, 50, null, null, null, "VOID").get("counts");
        assertEquals(2L, counts.get("ISSUED"), "the counts cover every state, whatever is filtered");
        assertEquals(0L, counts.get("VOID"));
    }

    @Test void theCouponCsvIsReadableAndSafe() {
        newSession();
        long booth = booths.insert(sessionId, "굿즈\r\n2층", null);
        Ticket a = boundTicket("csv@example.com", "=1+1");
        give(booth, "엽서, 2+1", null, List.of(a.id));
        String csv = new String(couponAdmin.export(sessionId, null, null, null, null).getBody(),
            java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("\uFEFF쿠폰,부스,입장권"));
        assertTrue(csv.contains("\"엽서, 2+1\""), "a comma stays inside its cell");
        assertTrue(csv.contains(",'=1+1,"), "a cell is never read as a formula");
        assertTrue(csv.contains(",\"굿즈\r\n2층\","), "a line break stays inside its quoted cell");
        assertFalse(csv.contains("csv@example.com"), "addresses are masked as on screen");
    }

    @Test void anOfferIsRewordedWithoutChangingWhatWasGiven() {
        newSession();
        long booth = booths.insert(sessionId, "커피", null);
        Ticket a = boundTicket("reword@example.com", null);
        give(booth, "웰컴 드링크", "1잔", List.of(a.id));
        long offerId = offers.findByBooth(booth).get(0).id;
        coupons.defineOffer(sessionId, booth, "사이즈 업", null);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
            () -> couponAdmin.updateOffer(offerId, Map.of("title", "사이즈 업"))).getStatusCode());
        couponAdmin.updateOffer(offerId, Map.of("title", "웰컴 음료", "detail", "2잔"));
        assertEquals("웰컴 음료", offers.findById(offerId).orElseThrow().title);
        assertEquals("웰컴 드링크", couponRepository.findByTicket(a.id).get(0).title, "what was given keeps its wording");
    }

    @Test void anOfferGoesToEveryTicketAndOnlyOnce() {
        newSession();
        Ticket first = boundTicket("one@example.com", "A-1");
        Ticket second = boundTicket("two@example.com", "A-2");
        long booth = booths.insert(sessionId, "커피 스탠드", "로비 왼쪽");

        Map<String, Object> given = give(booth, "웰컴 커피", "1인 1잔", List.of());
        assertEquals(2, given.get("given"));

        // Running it again for the latecomers is not an error, and nobody gets two.
        Ticket third = boundTicket("three@example.com", "A-3");
        Map<String, Object> again = give(booth, "웰컴 커피", "1인 1잔", List.of());
        assertEquals(1, again.get("given"));
        assertEquals(2, again.get("already"));
        assertEquals(1, couponRepository.findByTicket(first.id).size());
        assertEquals(1, couponRepository.findByTicket(third.id).size());
        assertEquals(1, couponRepository.findByTicket(second.id).size());
    }

    @Test void aBoothTerminalHandsOverTheCouponWhoseCodeItReads() {
        newSession();
        Ticket ticket = boundTicket("holder@example.com", "A-1");
        long booth = booths.insert(sessionId, "커피 스탠드", null);
        give(booth, "웰컴 커피", "1인 1잔", List.of(ticket.id));
        Gate stand = boothGate(booth);
        Coupon coffee = waiting(ticket, "웰컴 커피");

        Map<String, Object> first = coupons.redeemByCode(stand, couponCode(ticket, coffee));
        assertEquals("REDEEMED", first.get("outcome"));
        assertEquals("웰컴 커피", first.get("title"));
        assertEquals(0, first.get("remaining"));

        Coupon stored = couponRepository.findById(coffee.id).orElseThrow();
        assertEquals("REDEEMED", stored.status);
        assertEquals(stand.id, stored.redeemedGate);
        // A used coupon's code does not open again.
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
            () -> coupons.openCode(ticket, coffee.id)).getStatusCode());
    }

    @Test void aCouponIsUsedOnlyAtItsOwnBooth() {
        newSession();
        Ticket ticket = boundTicket("holder@example.com", "A-1");
        long coffee = booths.insert(sessionId, "커피 스탠드", null);
        long merch = booths.insert(sessionId, "굿즈 부스", null);
        give(coffee, "웰컴 커피", null, List.of(ticket.id));

        ResponseStatusException refused = assertThrows(ResponseStatusException.class,
            () -> coupons.redeemByCode(boothGate(merch), couponCode(ticket, waiting(ticket, "웰컴 커피"))));
        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode());
        assertTrue(String.valueOf(refused.getReason()).contains("커피 스탠드 쿠폰"), refused.getReason());
        assertEquals("ISSUED", waiting(ticket, "웰컴 커피").status);
    }

    /** The entry code is not a coupon, and a coupon's code is not an entry. */
    @Test void entryCodesAndCouponCodesAreNotInterchangeable() {
        newSession();
        Ticket ticket = boundTicket("holder@example.com", "A-1");
        long booth = booths.insert(sessionId, "커피 스탠드", null);
        give(booth, "웰컴 커피", null, List.of(ticket.id));
        Gate stand = boothGate(booth);

        ResponseStatusException entry = assertThrows(ResponseStatusException.class,
            () -> coupons.redeemByCode(stand, code(ticket)));
        assertTrue(String.valueOf(entry.getReason()).contains("입장 QR"), entry.getReason());
        assertEquals("ISSUED", waiting(ticket, "웰컴 커피").status, "nothing is spent by showing the wrong code");

        ResponseStatusException door = assertThrows(ResponseStatusException.class,
            () -> admissions.admit(entrance(), couponCode(ticket, waiting(ticket, "웰컴 커피")), "QR"));
        assertTrue(String.valueOf(door.getReason()).contains("쿠폰 QR"), door.getReason());
    }

    /** The same code cannot be walked around the room: a stand spends it like a door. */
    @Test void aCouponCodeShownAtAStandIsSpent() {
        newSession();
        Ticket ticket = boundTicket("holder@example.com", "A-1");
        long booth = booths.insert(sessionId, "커피 스탠드", null);
        give(booth, "웰컴 커피", null, List.of(ticket.id));
        Gate stand = boothGate(booth);

        String shown = couponCode(ticket, waiting(ticket, "웰컴 커피"));
        assertEquals("REDEEMED", coupons.redeemByCode(stand, shown).get("outcome"));
        ResponseStatusException reused = assertThrows(ResponseStatusException.class,
            () -> coupons.redeemByCode(stand, shown));
        assertTrue(String.valueOf(reused.getReason()).contains("사용"), reused.getReason());
    }

    /** Only the holder's own, unused, unvoided coupons open a code. */
    @Test void onlyALiveCouponOfThisTicketOpensACode() {
        newSession();
        Ticket mine = boundTicket("mine@example.com", null);
        Ticket theirs = boundTicket("theirs@example.com", null);
        long booth = booths.insert(sessionId, "커피 스탠드", null);
        give(booth, "웰컴 커피", null, List.of(mine.id, theirs.id));
        Coupon other = waiting(theirs, "웰컴 커피");
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
            () -> coupons.openCode(mine, other.id)).getStatusCode());
        couponRepository.setStatus(waiting(mine, "웰컴 커피").id, "VOID");
        Coupon voided = couponRepository.findByTicket(mine.id).get(0);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
            () -> coupons.openCode(mine, voided.id)).getStatusCode());
    }

    /** The stand's own screen can say what it gives and how much of it is left. */
    @Test void aBoothTerminalKnowsWhatItHandsOut() {
        newSession();
        Ticket first = boundTicket("first@example.com", "A-1");
        Ticket second = boundTicket("second@example.com", "A-2");
        long booth = booths.insert(sessionId, "커피 스탠드", null);
        give(booth, "웰컴 커피", null, List.of());
        give(booth, "리필", null, List.of(first.id));
        Gate stand = boothGate(booth);
        coupons.redeemByCode(stand, couponCode(first, waiting(first, "웰컴 커피")));

        List<Map<String, Object>> offers = coupons.boothOffers(sessionId, booth);
        assertEquals(2, offers.size(), "이 부스의 쿠폰 종류만 셉니다");
        Map<String, Object> coffee = offers.stream()
            .filter(offer -> "웰컴 커피".equals(offer.get("title"))).findFirst().orElseThrow();
        assertEquals(2, coffee.get("issued"));
        assertEquals(1, coffee.get("redeemed"), "한 사람이 받아 갔습니다");
        assertEquals(1, coffee.get("waiting"));
        assertEquals(0, coupons.boothOffers(sessionId,
            booths.insert(sessionId, "굿즈 부스", null)).size(), "다른 부스 것은 세지 않습니다");
        assertNotNull(second);
    }

    @Test void theCodeOpensSixtyTimesAnHourAndThenSaysHowLongToWait() {
        newSession();
        Ticket a = boundTicket("often@example.com", null);
        for (int i = 0; i < 60; i++) presentations.issue(a, i % 2 == 0 ? "IN" : "OUT", true);
        ResponseStatusException tooMany = assertThrows(ResponseStatusException.class,
            () -> presentations.issue(a, "IN", true));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, tooMany.getStatusCode());
        assertTrue(tooMany.getReason().matches(".*60번까지.*\\d+분 뒤에.*"), tooMany.getReason());
    }

    @Test void theHolderSeesWhatTheyHoldAndWhatTheySpent() {
        newSession();
        Ticket ticket = boundTicket("holder@example.com", "A-1");
        long booth = booths.insert(sessionId, "커피 스탠드", "로비 왼쪽");
        give(booth, "웰컴 커피", "따뜻한 음료 1잔", List.of(ticket.id));
        give(booth, "리필", "오후 6시까지", List.of(ticket.id));
        Gate stand = boothGate(booth);
        coupons.redeemByCode(stand, couponCode(ticket, waiting(ticket, "웰컴 커피")));

        List<Map<String, Object>> held = coupons.forTicket(sessionId, ticket.id);
        assertEquals(2, held.size());
        assertEquals("커피 스탠드", held.get(0).get("booth"));
        assertEquals("따뜻한 음료 1잔", held.get(0).get("detail"), "상세는 준 그대로 남습니다");
        assertEquals("REDEEMED", held.get(0).get("status"));
        assertEquals("ISSUED", held.get(1).get("status"));

        // And the ticket screen carries them without being asked separately.
        Map<String, Object> view = tickets.view(tickets.resolve(sessionId, tokenOf(ticket)));
        assertEquals(2, ((List<?>) view.get("coupons")).size());
    }

    /** Reads the ticket at a stand where it holds more than one coupon. */
    /** A quantity tops each ticket up to that many; running it again adds nothing. */
    @Test void aQuantityTopsEachTicketUpToThatMany() {
        newSession();
        Ticket first = boundTicket("one@example.com", "A-1");
        Ticket second = boundTicket("two@example.com", "A-2");
        long booth = booths.insert(sessionId, "커피 스탠드", null);
        long refill = coupons.defineOffer(sessionId, booth, "리필", null).id;

        coupons.issue(sessionId, refill, List.of(first.id), 1);
        Map<String, Object> batch = coupons.issue(sessionId, refill, List.of(), 3);
        assertEquals(5, batch.get("given"), "첫 번째는 2장, 두 번째는 3장을 더 받습니다");
        assertEquals(2, batch.get("tickets"));
        assertEquals(3, couponRepository.findByTicket(first.id).size());
        assertEquals(3, couponRepository.findByTicket(second.id).size());

        Map<String, Object> again = coupons.issue(sessionId, refill, List.of(), 3);
        assertEquals(0, again.get("given"));
        assertEquals(2, again.get("already"));

        ResponseStatusException tooMany = assertThrows(ResponseStatusException.class,
            () -> coupons.issue(sessionId, refill, List.of(), CouponService.MAX_PER_TICKET + 1));
        assertEquals(400, tooMany.getStatusCode().value());
    }

    /** More than one waiting: the counter picks kinds and counts, and only that is spent. */
    /** A ticket on its way to someone else spends none of its coupons on the way. */
    @Test void aTicketBeingTransferredOpensAndSpendsNoCoupon() {
        newSession();
        Ticket ticket = boundTicket("leaving@example.com", null);
        long booth = booths.insert(sessionId, "커피 스탠드", null);
        give(booth, "웰컴 커피", null, List.of(ticket.id));
        Coupon coffee = waiting(ticket, "웰컴 커피");
        String shown = couponCode(ticket, coffee);
        ticketRepository.updateStatus(ticket.id, "TRANSFER_PENDING");
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
            () -> coupons.openCode(ticket, coffee.id)).getStatusCode());
        assertThrows(ResponseStatusException.class, () -> coupons.redeemByCode(boothGate(booth), shown));
        assertEquals("ISSUED", couponRepository.findById(coffee.id).orElseThrow().status);
    }

    /** A coupon given back after being voided is unused again, with no hand-over on record. */
    @Test void aRestoredCouponCarriesNoOldHandOver() {
        newSession();
        Ticket ticket = boundTicket("restore@example.com", null);
        long booth = booths.insert(sessionId, "커피 스탠드", null);
        give(booth, "웰컴 커피", null, List.of(ticket.id));
        Coupon coffee = waiting(ticket, "웰컴 커피");
        coupons.redeemByCode(boothGate(booth), couponCode(ticket, coffee));
        assertEquals(1, couponAdmin.setStatuses(sessionId, Map.of("couponIds", List.of(coffee.id), "status", "VOID")).get("changed"));
        assertEquals(1, couponAdmin.setStatuses(sessionId, Map.of("couponIds", List.of(coffee.id), "status", "ISSUED")).get("changed"));
        Coupon back = couponRepository.findById(coffee.id).orElseThrow();
        assertEquals("ISSUED", back.status);
        assertNull(back.redeemedAt);
        assertNull(back.redeemedGate);
    }

    /** Several coupons at one booth: each has its own code and spends only itself. */
    @Test void eachCouponAtABoothHasItsOwnCode() {
        newSession();
        Ticket ticket = boundTicket("holder@example.com", "A-1");
        long booth = booths.insert(sessionId, "커피 스탠드", null);
        give(booth, "웰컴 커피", null, List.of(ticket.id));
        coupons.issue(sessionId, coupons.defineOffer(sessionId, booth, "리필", null).id, List.of(ticket.id), 2);
        Gate stand = boothGate(booth);

        Coupon refill = waiting(ticket, "리필");
        Map<String, Object> handed = coupons.redeemByCode(stand, couponCode(ticket, refill));
        assertEquals("리필", handed.get("title"));
        assertEquals(2, handed.get("remaining"), "웰컴 커피 1장과 리필 1장이 남습니다");
        assertEquals("REDEEMED", couponRepository.findById(refill.id).orElseThrow().status, "the one shown is the one spent");

        Coupon coffee = waiting(ticket, "웰컴 커피");
        assertEquals("웰컴 커피", coupons.redeemByCode(stand, couponCode(ticket, coffee)).get("title"));
        assertEquals(1, couponRepository.findByTicket(ticket.id).stream()
            .filter(coupon -> "ISSUED".equals(coupon.status)).count());
    }

    private Gate entrance() {
        String id = "e" + Secrets.randomAlnum(10);
        gates.insert(id, sessionId, "정문", "로비", "IN", gateAuth.hash("t-" + id), null, null, "ADMISSION", null);
        return gates.findById(id).orElseThrow();
    }

    /** A terminal gives only what the console put on its list, and only while it is on. */
    @Test void aTerminalGivesWhatItWasToldToAndHowMany() {
        newSession();
        Ticket ticket = boundTicket("holder@example.com", "A-1");
        long booth = booths.insert(sessionId, "커피 스탠드", null);
        long coffee = coupons.defineOffer(sessionId, booth, "웰컴 커피", null).id;
        long refill = coupons.defineOffer(sessionId, booth, "리필", null).id;
        Gate stand = boothGate(booth);

        ResponseStatusException none = assertThrows(ResponseStatusException.class,
            () -> coupons.grantByCode(stand, code(ticket)));
        assertEquals(409, none.getStatusCode().value(), "줄 쿠폰이 지정되지 않은 단말");

        // A booth cannot give on entry: it admits nobody.
        var set = coupons.setGateGrants(stand, Map.of(refill, true));
        assertFalse(set.get(0).autoOnEntry());

        Map<String, Object> read = coupons.grantByCode(stand, code(ticket));
        assertEquals("CHOOSE", read.get("outcome"));
        List<?> choices = (List<?>) read.get("choices");
        assertEquals(1, choices.size(), "지정한 리필만");
        String pick = String.valueOf(read.get("pick"));

        ResponseStatusException notListed = assertThrows(ResponseStatusException.class,
            () -> coupons.grantPicked(stand, pick, Map.of(coffee, 1)));
        assertEquals(409, notListed.getStatusCode().value());
        // A refused answer changed nothing, so the same pick can still be answered.
        assertEquals("GRANTED", coupons.grantPicked(stand, pick, Map.of(refill, 1)).get("outcome"));
        // And once answered, it is spent.
        assertThrows(ResponseStatusException.class, () -> coupons.grantPicked(stand, pick, Map.of(refill, 1)));

        Map<String, Object> given = coupons.grantPicked(stand,
            String.valueOf(coupons.grantByCode(stand, code(ticket)).get("pick")), Map.of(refill, 2));
        assertEquals("GRANTED", given.get("outcome"));
        assertEquals("리필 ×2", given.get("title"));
        assertEquals(3, couponRepository.findByTicket(ticket.id).size());

        // A count that would wrap around is a count that is too large, not a small one.
        String huge = String.valueOf(coupons.grantByCode(stand, code(ticket)).get("pick"));
        assertEquals(400, assertThrows(ResponseStatusException.class,
            () -> coupons.grantPicked(stand, huge, Map.of(refill, Integer.MAX_VALUE))).getStatusCode().value());
        assertEquals(3, couponRepository.findByTicket(ticket.id).size());

        String tooMany = String.valueOf(coupons.grantByCode(stand, code(ticket)).get("pick"));
        assertEquals(409, assertThrows(ResponseStatusException.class,
            () -> coupons.grantPicked(stand, tooMany, Map.of(refill, CouponService.MAX_PER_TICKET - 2)))
            .getStatusCode().value(), "한 사람에게 최대 장수까지만");

        // Stopping the offer takes it off every terminal's list.
        coupons.setOfferActive(refill, false);
        assertTrue(coupons.gateGrants(stand).isEmpty());
    }

    /** An entrance set to give on entry gives one, once, however many times they come in. */
    @Test void anEntranceGivesItsCouponOnceOnEntry() {
        newSession();
        Ticket ticket = boundTicket("holder@example.com", "A-1");
        long booth = booths.insert(sessionId, "커피 스탠드", null);
        long coffee = coupons.defineOffer(sessionId, booth, "웰컴 커피", null).id;
        Gate door = entrance();
        assertTrue(coupons.setGateGrants(door, Map.of(coffee, true)).get(0).autoOnEntry());

        Map<String, Object> admitted = admissions.admit(door, code(ticket), "QR");
        assertEquals("ADMITTED", admitted.get("outcome"));
        assertEquals(List.of("웰컴 커피"), admitted.get("granted"));
        assertEquals(1, couponRepository.findByTicket(ticket.id).size());

        assertTrue(coupons.autoGrantOnEntry(door, ticketRepository.findById(ticket.id).orElseThrow()).isEmpty(),
            "다시 들어와도 한 장");
        assertEquals(1, couponRepository.findByTicket(ticket.id).size());
    }

    /** Resolving needs the token, which only the issue call returns; re-issue to get one. */
    private String tokenOf(Ticket ticket) {
        String url = String.valueOf(tickets.reissueForConsole(ticket.id, false).get("url"));
        return url.substring(url.lastIndexOf('/') + 1);
    }
}
