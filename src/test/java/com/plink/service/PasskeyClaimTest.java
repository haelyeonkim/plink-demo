package com.plink.service;

import com.plink.model.ProtectedLink;
import com.plink.repository.LinkRecipientRepository;
import com.plink.ticket.repository.HolderRepository;
import com.plink.ticket.service.TicketService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two phones finishing the same unclaimed address: exactly one of them gets it, and the
 * other is refused rather than quietly bound alongside.
 */
// Isolated from ./.env: the suite must not depend on a developer's local configuration.
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:passkeyclaimtest;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.config.import=", "plink.admin.email=", "plink.admin.password=",
    "plink.auth.base-url=http://localhost:3000"})
class PasskeyClaimTest {

    @Autowired LinkService links;
    @Autowired LinkRecipientRepository recipients;
    @Autowired HolderRepository holders;
    @Autowired TicketService tickets;
    @Autowired PasskeyService passkeys;

    private long holder(String email) {
        return holders.create(email, tickets.userHandleFor(email));
    }

    @Test void onlyTheFirstBindTakesTheAddress() {
        ProtectedLink link = links.createLink("https://example.com/race", "경합", null, null, null, 0, "owner");
        long recipient = links.issue(link.getId(), "race@example.com", null).id;
        long first = holder("race@example.com");
        long second = holder("someone-else@example.com");

        assertEquals(1, recipients.bindIfUnclaimed(recipient, first));
        assertEquals(0, recipients.bindIfUnclaimed(recipient, second), "이미 확정된 주소는 다시 묶이지 않아야 합니다");
        assertEquals(first, recipients.findById(recipient).orElseThrow().holderId);
    }

    @Test void aCeremonyStartedBeforeSomebodyElseClaimedItIsRefused() {
        ProtectedLink link = links.createLink("https://example.com/late", "늦은 확정", null, null, null, 0, "owner");
        var issued = links.issue(link.getId(), "late@example.com", null);
        long winner = holder("late@example.com");
        long loser = holder("loser@example.com");

        // This phone started while the address was free...
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("plink.passkey.pending",
            new PasskeyService.Pending(issued.shortCode, "loser@example.com", issued.id, loser, null, null));
        // ...and another finished first.
        recipients.bindIfUnclaimed(issued.id, winner);

        ResponseStatusException refused = assertThrows(ResponseStatusException.class, () -> passkeys.finish(
            issued.shortCode, new ObjectMapper().readTree("{\"id\":\"x\"}"), session));
        assertEquals(409, refused.getStatusCode().value());
        assertEquals(winner, recipients.findById(issued.id).orElseThrow().holderId);
        assertEquals(0, links.getLink(link.getId()).orElseThrow().getViewCount(), "열람으로 세지 않아야 합니다");
    }
}
