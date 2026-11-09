package com.plink;

import com.plink.repository.*;
import com.plink.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:artworkrequests;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.config.import=",
    "plink.admin.email=", "plink.admin.password=", "plink.auth.base-url=http://localhost:3000"})
@AutoConfigureMockMvc
class ArtworkRequestTest {
    @Autowired ArtworkRequestService requests;
    @Autowired ArtworkStatusService statuses;
    @Autowired ArtworkHoldExpiry expiry;
    @Autowired ContentRepository contents;
    @Autowired ArtworkRepository artworks;
    @Autowired ArtworkDeliveryService deliveries;
    @Autowired ContentImageService images;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    final ObjectMapper json = new ObjectMapper();
    record Reader(long artwork, ArtworkDeliveryService.Delivery delivery, MockHttpSession session) {
        String code() { return delivery.recipient().shortCode; }
        String url() { return "/api/links/s/" + code() + "/artwork-requests"; }
    }
    @BeforeEach void admins() { AdminArtworkTest.ensureAdmins(jdbc); }
    Reader reader() {
        String owner = UUID.randomUUID().toString();
        long content = contents.insert(owner, "Catalog", "EXHIBITION", "{\"artworks\":[{\"title\":\"Blue\"}]}");
        artworks.replace(content, owner, List.of(Map.of("title", "Blue")));
        return reader(owner, artworks.findByOwner(owner).get(0).id());
    }
    Reader reader(String owner, long artwork) {
        var delivery = deliveries.create(owner, List.of(artwork), UUID.randomUUID() + "@example.com", "", "Selection", null, null, 0, false);
        var session = new MockHttpSession(); images.grant(session, delivery.link(), delivery.recipient());
        return new Reader(artwork, delivery, session);
    }
    ArtworkRequestService.Command command(String action, long version) {
        return new ArtworkRequestService.Command(action, version, UUID.randomUUID().toString(),
            action.equals("APPROVE") ? Instant.now().plusSeconds(3600) : null, "처리 사유", false);
    }
    ArtworkRequestService.State act(Reader r, String action, long version) {
        return requests.recipient(r.code(), r.artwork(), command(action, version), r.session());
    }
    @Test void recipientLifecycleKeepsInterestAndRecordsEveryTransition() {
        Reader r = reader();
        var s = act(r, "INTEREST", 0);
        s = act(r, "REQUEST_HOLD", s.version());
        s = requests.admin(s.id(), command("APPROVE", s.version()), "admin");
        assertThat(s.holdState()).isEqualTo("HELD");
        assertThat(statuses.statuses(r.delivery().link().getContentId()).get(0).saleStatus()).isEqualTo("hold");
        s = act(r, "PURCHASE", s.version());
        s = act(r, "WITHDRAW", s.version());
        assertThat(s.holdState()).isEqualTo("HELD");
        s = act(r, "CANCEL_HOLD", s.version());
        assertThat(s.interested()).isTrue();
        assertThat(s.holdState()).isEqualTo("CANCELLED");
        assertThat(statuses.statuses(r.delivery().link().getContentId()).get(0).saleStatus()).isNull();
        assertThat(requests.history(s.id())).hasSize(6);
        s = act(r, "REQUEST_HOLD", s.version());
        s = requests.admin(s.id(), command("REJECT", s.version()), "admin");
        assertThat(s.holdState()).isEqualTo("REJECTED");
        assertThat(requests.history(s.id())).hasSize(8);
    }
    @Test void saleRequiresPurchaseIntentAndCancellationRequiresAdmin() {
        Reader r = reader(); var s = act(r, "PURCHASE", 0);
        s = requests.admin(s.id(), command("SELL", s.version()), "admin");
        final long version = s.version();
        assertThatThrownBy(() -> act(r, "WITHDRAW", version)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> statuses.change(r.artwork(), null)).isInstanceOf(ResponseStatusException.class);
        s = act(r, "REQUEST_CANCEL", version);
        s = requests.admin(s.id(), command("KEEP_SALE", s.version()), "admin");
        assertThat(s.purchased()).isTrue(); assertThat(s.cancellationRequested()).isFalse();
        s = act(r, "REQUEST_CANCEL", s.version());
        s = requests.admin(s.id(), command("CANCEL_SALE", s.version()), "admin");
        assertThat(s.purchased()).isFalse(); assertThat(s.purchaseIntent()).isFalse();
        assertThat(statuses.statuses(r.delivery().link().getContentId()).get(0).saleStatus()).isNull();
    }
    @Test void withdrawCanReleaseHoldAndExpiryDoesNotRemovePurchaseIntent() {
        Reader r = reader(); var s = act(r, "REQUEST_HOLD", 0);
        s = requests.admin(s.id(), command("APPROVE", s.version()), "admin");
        s = act(r, "PURCHASE", s.version());
        s = requests.recipient(r.code(), r.artwork(), new ArtworkRequestService.Command("WITHDRAW", s.version(), UUID.randomUUID().toString(), null, "", true), r.session());
        assertThat(s.holdState()).isEqualTo("CANCELLED");
        s = act(r, "REQUEST_HOLD", s.version());
        s = requests.admin(s.id(), command("APPROVE", s.version()), "admin");
        s = act(r, "PURCHASE", s.version());
        jdbc.update("UPDATE artwork_request SET hold_until=? WHERE id=?", java.sql.Timestamp.from(Instant.now().minusSeconds(60)), s.id());
        expiry.expire(); expiry.expire();
        s = requests.mine(r.code(), r.session()).get(0);
        assertThat(s.holdState()).isEqualTo("EXPIRED"); assertThat(s.purchaseIntent()).isTrue();
        assertThat(requests.history(s.id()).stream().filter(e -> e.get("action").equals("EXPIRED")).count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM artwork_reservation WHERE artwork_id=?", Integer.class, r.artwork())).isZero();
    }
    @Test void repeatedCommandIsIdempotentAndStaleVersionCannotOverwriteNewState() {
        Reader r = reader(); var command = command("INTEREST", 0);
        var first = requests.recipient(r.code(), r.artwork(), command, r.session());
        var second = requests.recipient(r.code(), r.artwork(), command, r.session());
        assertThat(second).isEqualTo(first); assertThat(requests.history(first.id())).hasSize(1);
        assertThatThrownBy(() -> act(r, "UNINTEREST", 0)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> requests.recipient(r.code(), r.artwork(),
            new ArtworkRequestService.Command("UNINTEREST", first.version(), command.key(), null, "", false), r.session()))
            .isInstanceOf(ResponseStatusException.class);
    }
    @Test void sessionMembershipRevocationCsrfAndAdminScopeAreEnforced() throws Exception {
        Reader r = reader(), stranger = reader();
        String body = json.writeValueAsString(command("INTEREST", 0));
        mvc.perform(get(r.url())).andExpect(status().isForbidden());
        mvc.perform(post(r.url()+"/"+r.artwork()).session(r.session()).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post(r.url()+"/"+r.artwork()).with(csrf()).session(stranger.session()).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post(r.url()+"/"+stranger.artwork()).with(csrf()).session(r.session()).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post(r.url()+"/"+r.artwork()).with(csrf()).session(r.session()).contentType("application/json").content(body)).andExpect(status().isOk());
        var s = requests.mine(r.code(), r.session()).get(0);
        mvc.perform(get("/api/admin/artworks/requests").with(AdminArtworkTest.admin(false))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/artworks/requests/"+s.id()+"/history").with(AdminArtworkTest.admin(false))).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/artworks/requests/"+s.id()).with(AdminArtworkTest.admin(false)).with(csrf()).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/artworks/requests").with(AdminArtworkTest.admin(true))).andExpect(status().isOk());
        mvc.perform(post(r.url()+"/"+r.artwork()).with(csrf()).session(r.session()).contentType("application/json").content(json.writeValueAsString(command("APPROVE", s.version())))).andExpect(status().isBadRequest());
        jdbc.update("UPDATE link_recipient SET status='REVOKED' WHERE id=?", r.delivery().recipient().id);
        mvc.perform(get(r.url()).session(r.session())).andExpect(status().isForbidden());
        assertThat(requests.history(s.id())).hasSize(1);
    }
    @Test void failedAuditInsertRollsBackStateChange() {
        Reader r = reader(); var s = act(r, "REQUEST_HOLD", 0);
        assertThatThrownBy(() -> requests.admin(s.id(), command("APPROVE", s.version()), "x".repeat(256))).isInstanceOf(RuntimeException.class);
        assertThat(requests.mine(r.code(), r.session()).get(0).holdState()).isEqualTo("PENDING");
        assertThat(statuses.statuses(r.delivery().link().getContentId()).get(0).saleStatus()).isNull();
        assertThat(requests.history(s.id())).hasSize(1);
    }
    @Test void concurrentApprovalsHaveExactlyOneWinner() throws Exception {
        Reader first = reader();
        Reader second = reader(first.delivery().link().getOwnerSub(), first.artwork());
        var one = act(first, "REQUEST_HOLD", 0); var two = act(second, "REQUEST_HOLD", 0);
        var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var futures = new ArrayList<Future<Boolean>>();
            for (var s : List.of(one, two)) futures.add(pool.submit(() -> {
                start.await();
                try { requests.admin(s.id(), command("APPROVE", s.version()), "admin"); return true; }
                catch (ResponseStatusException conflict) { assertThat(conflict.getStatusCode().value()).isEqualTo(409); return false; }
            }));
            start.countDown(); int winners = 0;
            for (var f : futures) if (f.get(10, TimeUnit.SECONDS)) winners++;
            assertThat(winners).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM artwork_reservation WHERE artwork_id=?", Integer.class, first.artwork())).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
    @Test void expiredAndDeletedLinksKeepHistoryAndSourceCannotBeDeleted() throws Exception {
        Reader r = reader(); var state = act(r, "REQUEST_HOLD", 0);
        jdbc.update("UPDATE protected_link SET expires_at=? WHERE id=?", java.sql.Timestamp.from(Instant.now().minusSeconds(5)), r.delivery().link().getId());
        mvc.perform(get(r.url()).session(r.session())).andExpect(status().isForbidden());
        mvc.perform(post(r.url()+"/"+r.artwork()).with(csrf()).session(r.session()).contentType("application/json")
            .content(json.writeValueAsString(command("CANCEL_HOLD", state.version())))).andExpect(status().isForbidden());
        jdbc.update("DELETE FROM link_recipient WHERE id=?", r.delivery().recipient().id);
        jdbc.update("DELETE FROM protected_link WHERE id=?", r.delivery().link().getId());
        assertThat(requests.history(state.id())).hasSize(1);
        var approved = requests.admin(state.id(), command("REJECT", state.version()), "admin");
        assertThat(approved.holdState()).isEqualTo("REJECTED");
        long source = jdbc.queryForObject("SELECT source_content_id FROM artwork WHERE id=?", Long.class, r.artwork());
        mvc.perform(delete("/api/contents/"+source).with(oidcLogin().idToken(t -> t.subject(r.delivery().link().getOwnerSub()))).with(csrf()))
            .andExpect(status().isConflict());
        assertThat(requests.history(state.id())).hasSize(2);
    }
    @Test void cancellingAfterApprovalNeedsLatestVersionAndDoesNotAffectAnotherRecipient() {
        Reader first = reader(); Reader second = reader(first.delivery().link().getOwnerSub(), first.artwork());
        var waiting = act(first, "REQUEST_HOLD", 0);
        var approved = requests.admin(waiting.id(), command("APPROVE", waiting.version()), "admin");
        assertThatThrownBy(() -> act(first, "CANCEL_HOLD", waiting.version())).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> act(second, "CANCEL_HOLD", 0)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> act(second, "REQUEST_HOLD", 0)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> act(second, "PURCHASE", 0)).isInstanceOf(ResponseStatusException.class);
        assertThat(requests.mine(second.code(), second.session())).isEmpty();
        var cancelled = act(first, "CANCEL_HOLD", approved.version());
        assertThat(cancelled.holdState()).isEqualTo("CANCELLED");
        assertThat(act(second, "REQUEST_HOLD", 0).holdState()).isEqualTo("PENDING");
    }
    @Test void legacyStatusChangeClosesPendingRequestsButCannotOverwriteActiveHold() {
        Reader r = reader(); var s = act(r, "REQUEST_HOLD", 0);
        statuses.change(r.artwork(), "hold");
        assertThat(requests.mine(r.code(), r.session()).get(0).holdState()).isEqualTo("CLOSED");
        statuses.change(r.artwork(), null);
        s = act(r, "REQUEST_HOLD", requests.mine(r.code(), r.session()).get(0).version());
        requests.admin(s.id(), command("APPROVE", s.version()), "admin");
        assertThatThrownBy(() -> statuses.change(r.artwork(), "sold")).isInstanceOf(ResponseStatusException.class);
    }
}
