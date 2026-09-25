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

    private long sessionId;

    private long newSession() {
        sessionId = sessions.insert("쿠폰 행사 " + Secrets.randomAlnum(6), "메인홀",
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
        assertEquals("REDEEMED", coupons.redeemByCode(boothGate(booth), code(ticket)).get("outcome"),
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

        Map<String, Object> first = adminController.listCoupons(sessionId, 0, 4, null, null, null);
        assertEquals(10L, first.get("total"));
        assertEquals(4, ((List<?>) first.get("items")).size());
        assertEquals(2, ((List<?>) adminController.listCoupons(sessionId, 2, 4, null, null, null)
            .get("items")).size(), "마지막 쪽에는 남은 것만");

        assertEquals(5L, adminController.listCoupons(sessionId, 0, 50, null, merch, null).get("total"));
        assertEquals(1L, adminController.listCoupons(sessionId, 0, 50, "c-3", coffee, null).get("total"),
            "좌석으로 찾기");
        assertEquals(0L, adminController.listCoupons(sessionId, 0, 50, null, null, "REDEEMED").get("total"));
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

    @Test void aBoothTerminalHandsOneOverAndThenSaysItIsGone() {
        newSession();
        Ticket ticket = boundTicket("holder@example.com", "A-1");
        long booth = booths.insert(sessionId, "커피 스탠드", null);
        give(booth, "웰컴 커피", "1인 1잔", List.of(ticket.id));
        Gate stand = boothGate(booth);

        Map<String, Object> first = coupons.redeemByCode(stand, code(ticket));
        assertEquals("REDEEMED", first.get("outcome"));
        assertEquals("웰컴 커피", first.get("title"));
        assertEquals(0, first.get("remaining"));

        Map<String, Object> second = coupons.redeemByCode(stand, code(ticket));
        assertEquals("ALREADY", second.get("outcome"), "두 번째는 거부가 아니라 '이미 받아 갔다'입니다");
        assertNotNull(second.get("redeemedAt"));

        Coupon stored = couponRepository.findByTicket(ticket.id).get(0);
        assertEquals("REDEEMED", stored.status);
        assertEquals(stand.id, stored.redeemedGate);
    }

    @Test void aTicketWithNothingAtThisBoothIsTurnedAway() {
        newSession();
        Ticket ticket = boundTicket("holder@example.com", "A-1");
        long coffee = booths.insert(sessionId, "커피 스탠드", null);
        long merch = booths.insert(sessionId, "굿즈 부스", null);
        give(coffee, "웰컴 커피", null, List.of(ticket.id));

        ResponseStatusException refused = assertThrows(ResponseStatusException.class,
            () -> coupons.redeemByCode(boothGate(merch), code(ticket)));
        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode());
        assertTrue(String.valueOf(refused.getReason()).contains("굿즈 부스"), refused.getReason());
    }

    /** The same code cannot be walked around the room: a stand spends it like a door. */
    @Test void aCodeShownAtAStandIsSpent() {
        newSession();
        Ticket ticket = boundTicket("holder@example.com", "A-1");
        long booth = booths.insert(sessionId, "커피 스탠드", null);
        give(booth, "웰컴 커피", null, List.of(ticket.id));
        give(booth, "리필", null, List.of(ticket.id));
        Gate stand = boothGate(booth);

        String shown = code(ticket);
        assertEquals("REDEEMED", coupons.redeemByCode(stand, shown).get("outcome"));
        ResponseStatusException reused = assertThrows(ResponseStatusException.class,
            () -> coupons.redeemByCode(stand, shown));
        assertTrue(String.valueOf(reused.getReason()).contains("사용"), reused.getReason());

        // A fresh code still finds the second offer waiting.
        assertEquals("REDEEMED", coupons.redeemByCode(stand, code(ticket)).get("outcome"));
    }

    /** The stand's own screen can say what it gives and how much of it is left. */
    @Test void aBoothTerminalKnowsWhatItHandsOut() {
        newSession();
        Ticket first = boundTicket("first@example.com", "A-1");
        Ticket second = boundTicket("second@example.com", "A-2");
        long booth = booths.insert(sessionId, "커피 스탠드", null);
        give(booth, "웰컴 커피", null, List.of());
        give(booth, "리필", null, List.of(first.id));
        coupons.redeemByCode(boothGate(booth), code(first));

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

    @Test void theHolderSeesWhatTheyHoldAndWhatTheySpent() {
        newSession();
        Ticket ticket = boundTicket("holder@example.com", "A-1");
        long booth = booths.insert(sessionId, "커피 스탠드", "로비 왼쪽");
        give(booth, "웰컴 커피", "따뜻한 음료 1잔", List.of(ticket.id));
        give(booth, "리필", "오후 6시까지", List.of(ticket.id));
        coupons.redeemByCode(boothGate(booth), code(ticket));

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

    /** Resolving needs the token, which only the issue call returns; re-issue to get one. */
    private String tokenOf(Ticket ticket) {
        String url = String.valueOf(tickets.reissueForConsole(ticket.id, false).get("url"));
        return url.substring(url.lastIndexOf('/') + 1);
    }
}
