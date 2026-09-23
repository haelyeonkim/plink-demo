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
    @Autowired TicketService tickets;
    @Autowired TicketRepository ticketRepository;
    @Autowired EventSessionRepository sessions;
    @Autowired HolderRepository holders;
    @Autowired GateRepository gates;
    @Autowired GateAuthService gateAuth;
    @Autowired PresentationService presentations;

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

    @Test void anOfferGoesToEveryTicketAndOnlyOnce() {
        newSession();
        Ticket first = boundTicket("one@example.com", "A-1");
        Ticket second = boundTicket("two@example.com", "A-2");
        long booth = booths.insert(sessionId, "커피 스탠드", "로비 왼쪽");

        Map<String, Object> given = coupons.issue(sessionId, booth, "웰컴 커피", "1인 1잔", List.of());
        assertEquals(2, given.get("given"));

        // Running it again for the latecomers is not an error, and nobody gets two.
        Ticket third = boundTicket("three@example.com", "A-3");
        Map<String, Object> again = coupons.issue(sessionId, booth, "웰컴 커피", "1인 1잔", List.of());
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
        coupons.issue(sessionId, booth, "웰컴 커피", "1인 1잔", List.of(ticket.id));
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
        coupons.issue(sessionId, coffee, "웰컴 커피", null, List.of(ticket.id));

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
        coupons.issue(sessionId, booth, "웰컴 커피", null, List.of(ticket.id));
        coupons.issue(sessionId, booth, "리필", null, List.of(ticket.id));
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
        coupons.issue(sessionId, booth, "웰컴 커피", null, List.of());
        coupons.issue(sessionId, booth, "리필", null, List.of(first.id));
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
        coupons.issue(sessionId, booth, "웰컴 커피", "따뜻한 음료 1잔", List.of(ticket.id));
        coupons.issue(sessionId, booth, "리필", "오후 6시까지", List.of(ticket.id));
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
