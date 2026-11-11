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
    @Autowired com.plink.activity.ActivityLog activity;
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
        assertThat(s.purchased()).isTrue(); assertThat(s.cancellationRequested()).isFalse(); assertThat(s.cancelRejected()).isTrue();
        final long refused = s.version();
        // A refused cancellation is asked about, not requested again from the same button.
        assertThatThrownBy(() -> act(r, "REQUEST_CANCEL", refused)).isInstanceOf(ResponseStatusException.class);
        s = act(r, "INQUIRE", s.version());
        s = requests.admin(s.id(), command("ALLOW_RETRY", s.version()), "admin");
        assertThat(s.cancelRejected()).isFalse(); assertThat(s.inquiryPending()).isFalse();
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

    Reader ownedReader(String owner) {
        long content = contents.insert(owner, "Inbox catalog", "EXHIBITION", "{\"artworks\":[{\"title\":\"Inbox work\"}]}");
        artworks.replace(content, owner, List.of(Map.of("title", "Inbox work")));
        long artwork = jdbc.queryForObject("SELECT id FROM artwork WHERE source_content_id=?", Long.class, content);
        return reader(owner, artwork);
    }
    @Test void inboxScopesListSummaryHistoryAndMutationsAndKeepsPrivateNotesPrivate() throws Exception {
        Reader mine = ownedReader("local:998"), other = ownedReader("local:999");
        var one = act(mine, "PURCHASE", 0); var two = act(other, "PURCHASE", 0);
        String base = "/api/links/artwork-requests";
        mvc.perform(get(base).with(AdminArtworkTest.admin(false)).param("artworkId", Long.toString(other.artwork())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.counts.active").value(0));
        mvc.perform(get(base).with(AdminArtworkTest.admin(false)).param("artworkId", Long.toString(mine.artwork())))
            .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.counts.purchase").value(1));
        for (String suffix : List.of("/history", "/recipient-history", "/work-history")) {
            mvc.perform(get(base+"/"+two.id()+suffix).with(AdminArtworkTest.admin(false))).andExpect(status().isNotFound());
        }
        String work = "{\"version\":"+one.version()+",\"processingState\":\"CONTACTING\",\"assignee\":\"담당자 김\",\"internalNote\":\"비공개 상담 내용\"}";
        mvc.perform(post(base+"/"+two.id()+"/work").with(AdminArtworkTest.admin(false)).with(csrf()).contentType("application/json").content(work)).andExpect(status().isNotFound());
        mvc.perform(post(base+"/"+two.id()).with(AdminArtworkTest.admin(false)).with(csrf()).contentType("application/json").content(json.writeValueAsString(command("SELL", two.version())))).andExpect(status().isNotFound());
        mvc.perform(post(base+"/"+one.id()+"/work").with(AdminArtworkTest.admin(false)).with(csrf()).contentType("application/json").content(work)).andExpect(status().isNoContent());
        mvc.perform(post(base+"/"+one.id()+"/work").with(AdminArtworkTest.admin(false)).with(csrf()).contentType("application/json").content(work)).andExpect(status().isConflict());
        mvc.perform(get(base+"/"+one.id()+"/work-history").with(AdminArtworkTest.admin(false)))
            .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].after.processing_state").value("CONTACTING"))
            .andExpect(jsonPath("$.items[0].after.internal_note").value("비공개 상담 내용"));
        mvc.perform(get(mine.url()).session(mine.session())).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].internalNote").doesNotExist()).andExpect(jsonPath("$[0].assignee").doesNotExist());
        var changed = act(mine, "WITHDRAW", one.version()+1);
        mvc.perform(get(base).with(AdminArtworkTest.admin(false)).param("artworkId", Long.toString(mine.artwork())))
            .andExpect(jsonPath("$.items[0].processingState").value("UNREAD"))
            .andExpect(jsonPath("$.items[0].internalNote").value("비공개 상담 내용"));
        assertThat(changed.purchaseIntent()).isFalse();
    }
    @Test void queueDoesNotMoveOnAdminWorkOrDuplicateChoicesAndSummaryHasNoDoubleCounting() throws Exception {
        Reader first = ownedReader("local:998"), second = reader("local:998", first.artwork());
        var one = act(first, "INTEREST", 0); one = act(first, "REQUEST_HOLD", one.version()); one = act(first, "PURCHASE", one.version());
        var two = act(second, "PURCHASE", 0);
        jdbc.update("UPDATE artwork_request SET purchase_at=TIMESTAMP '2026-01-01 10:00:00' WHERE id=?", one.id());
        jdbc.update("UPDATE artwork_request SET purchase_at=TIMESTAMP '2026-01-02 10:00:00' WHERE id=?", two.id());
        var duplicate = act(first, "PURCHASE", one.version());
        assertThat(duplicate.version()).isEqualTo(one.version()); assertThat(requests.history(one.id())).hasSize(3);
        String base = "/api/links/artwork-requests";
        mvc.perform(get(base).with(AdminArtworkTest.admin(false)).param("artworkId", Long.toString(first.artwork())))
            .andExpect(jsonPath("$.items[0].id").value(one.id())).andExpect(jsonPath("$.counts.active").value(2))
            .andExpect(jsonPath("$.counts.purchase").value(2)).andExpect(jsonPath("$.counts.pending").value(0));
        mvc.perform(get(base).with(AdminArtworkTest.admin(false)).param("artworkId", Long.toString(first.artwork())).param("filter", "interested"))
            .andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.counts.purchase").value(2));
        mvc.perform(get(base).with(AdminArtworkTest.admin(false)).param("artworkId", Long.toString(first.artwork())).param("from", "2026-01-02").param("to", "2026-01-02"))
            .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].id").value(two.id()));
        one = act(first, "WITHDRAW", one.version()); one = act(first, "PURCHASE", one.version());
        mvc.perform(get(base).with(AdminArtworkTest.admin(false)).param("artworkId", Long.toString(first.artwork())))
            .andExpect(jsonPath("$.items[0].id").value(two.id()));
        mvc.perform(get(base).with(AdminArtworkTest.admin(false)).param("artworkId", Long.toString(first.artwork())).param("withdrawals", "true"))
            .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].withdrawalCount").value(1));
        mvc.perform(get(base).with(AdminArtworkTest.admin(false)).param("from", "2026-02-02").param("to", "2026-01-01"))
            .andExpect(status().isBadRequest());
    }
    @Test void recipientTimelineUsesVerifiedHolderAndAppliesOwnerBoundaryAcrossLinks() throws Exception {
        Reader first = ownedReader("local:998"), second = ownedReader("local:998"), other = ownedReader("local:999"), sameEmail = ownedReader("local:998");
        var one = act(first, "PURCHASE", 0); act(first, "WITHDRAW", one.version());
        var two = act(second, "PURCHASE", 0); act(second, "WITHDRAW", two.version());
        var hidden = act(other, "PURCHASE", 0); act(other, "WITHDRAW", hidden.version());
        var unverified = act(sameEmail, "PURCHASE", 0); act(sameEmail, "WITHDRAW", unverified.version());
        jdbc.update("UPDATE artwork_request SET holder_id=123456 WHERE id IN (?,?,?)", one.id(), two.id(), hidden.id());
        jdbc.update("UPDATE artwork_request SET recipient_email=? WHERE id=?", first.delivery().recipient().email, unverified.id());
        String url = "/api/links/artwork-requests/"+one.id()+"/recipient-history";
        mvc.perform(get(url).with(AdminArtworkTest.admin(false))).andExpect(jsonPath("$.total").value(4))
            .andExpect(jsonPath("$.purchases").value(2)).andExpect(jsonPath("$.withdrawals").value(2));
        mvc.perform(get(url).with(AdminArtworkTest.admin(true))).andExpect(jsonPath("$.total").value(6))
            .andExpect(jsonPath("$.withdrawals").value(3));
        mvc.perform(get(url).with(AdminArtworkTest.admin(false)).param("page", "1")).andExpect(jsonPath("$.items.length()").value(0))
            .andExpect(jsonPath("$.withdrawals").value(2));
        mvc.perform(get("/api/links/artwork-requests").with(AdminArtworkTest.admin(false)).param("artworkId", Long.toString(first.artwork())).param("sort", "withdrawals"))
            .andExpect(jsonPath("$.items[0].withdrawalCount").value(2));
    }

    @SuppressWarnings("unchecked")
    @Test void refusedHoldIsReopenedOnlyThroughAnInquiry() {
        Reader r = reader(); var s = act(r, "REQUEST_HOLD", 0);
        s = requests.admin(s.id(), new ArtworkRequestService.Command("REJECT", s.version(), UUID.randomUUID().toString(), null,
            "전시·대여 일정으로 홀딩이 어려워요", false), "admin");
        final long rejected = s.version();
        assertThatThrownBy(() -> act(r, "REQUEST_HOLD", rejected)).isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("담당자에게 문의");
        assertThatThrownBy(() -> requests.recipient(r.code(), r.artwork(),
            new ArtworkRequestService.Command("INQUIRE", rejected, UUID.randomUUID().toString(), null, " ", false), r.session()))
            .isInstanceOf(ResponseStatusException.class);
        var view = requests.view(s);
        assertThat((List<?>) view.get("notices")).anySatisfy(n -> assertThat((Map<String, Object>) n)
            .containsEntry("kind", "hold").containsEntry("action", "REJECT").containsEntry("reason", "전시·대여 일정으로 홀딩이 어려워요"));
        s = act(r, "INQUIRE", s.version());
        assertThat(s.inquiryPending()).isTrue();
        final long asked = s.version();
        assertThatThrownBy(() -> act(r, "INQUIRE", asked)).isInstanceOf(ResponseStatusException.class);
        s = requests.admin(s.id(), command("DECLINE_RETRY", s.version()), "admin");
        assertThat(s.inquiryPending()).isFalse(); assertThat(s.holdState()).isEqualTo("REJECTED");
        assertThat((List<?>) requests.view(s).get("notices")).anySatisfy(n -> assertThat((Map<String, Object>) n)
            .containsEntry("action", "DECLINE_RETRY").containsEntry("reason", "처리 사유"));
        s = act(r, "INQUIRE", s.version());
        s = requests.admin(s.id(), command("ALLOW_RETRY", s.version()), "admin");
        assertThat(s.holdState()).isEqualTo("NONE");
        s = act(r, "REQUEST_HOLD", s.version());
        assertThat(s.holdState()).isEqualTo("PENDING");
        // The inquiry, not only the hold, appears in the administrator's queue.
        assertThat(requests.history(s.id()).stream().map(e -> e.get("action")))
            .containsSubsequence("REQUEST_HOLD", "ALLOW_RETRY", "INQUIRE", "DECLINE_RETRY", "INQUIRE", "REJECT", "REQUEST_HOLD");
    }
    @SuppressWarnings("unchecked")
    @Test void requestClosedForAnotherRecipientIsExplainedInTheRecipientsWords() {
        Reader a = reader();
        Reader b = reader(a.delivery().link().getOwnerSub(), a.artwork());
        var first = act(a, "REQUEST_HOLD", 0);
        var second = act(b, "REQUEST_HOLD", 0);
        requests.admin(first.id(), command("APPROVE", first.version()), "admin");
        var closed = requests.mine(b.code(), b.session()).get(0);
        assertThat(closed.holdState()).isEqualTo("CLOSED");
        assertThat((List<?>) requests.view(closed).get("notices")).anySatisfy(n -> assertThat((Map<String, Object>) n)
            .containsEntry("kind", "hold").containsEntry("reason", "다른 분께 홀딩이 확정되어 요청이 종료되었어요."));
        assertThat(second.id()).isEqualTo(closed.id());
    }
    @Test void aFirstChoiceThatChangesNothingLeavesNoRequest() {
        Reader r = reader();
        act(r, "UNINTEREST", 0);
        assertThat(requests.mine(r.code(), r.session())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM artwork_request WHERE artwork_id=?", Integer.class, r.artwork())).isZero();
        var s = act(r, "INTEREST", 0);
        assertThat(s.version()).isEqualTo(1);
    }
    @Test void activityLogNamesTheArtworkAndRecipient() {
        Reader r = reader(); act(r, "PURCHASE", 0);
        activity.flush();
        var rows = jdbc.queryForList("SELECT summary, actor_email FROM activity_log WHERE action='artwork.purchase' AND actor_email=?",
            r.delivery().recipient().email);
        assertThat(rows).singleElement().satisfies(row -> assertThat((String) row.get("summary")).startsWith("Blue · "));
    }

    @SuppressWarnings("unchecked")
    @Test void administratorExtendsAHeldHoldWithoutReleasingIt() {
        Reader r = reader(); var s = act(r, "REQUEST_HOLD", 0);
        s = requests.admin(s.id(), command("APPROVE", s.version()), "admin");
        Instant current = s.holdUntil().toInstant();
        final var held = s;
        // Not earlier, not the same, and not without a time.
        for (Instant bad : new Instant[] { current.minusSeconds(60), current, null })
            assertThatThrownBy(() -> requests.admin(held.id(), new ArtworkRequestService.Command("EXTEND", held.version(),
                UUID.randomUUID().toString(), bad, "", false), "admin")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> requests.recipient(r.code(), r.artwork(), new ArtworkRequestService.Command("EXTEND", held.version(),
            UUID.randomUUID().toString(), current.plusSeconds(86400), "", false), r.session())).isInstanceOf(ResponseStatusException.class);
        Instant later = current.plusSeconds(3 * 86400);
        s = requests.admin(s.id(), new ArtworkRequestService.Command("EXTEND", s.version(), UUID.randomUUID().toString(), later,
            "고객 요청으로 기한을 연장했어요", false), "admin");
        assertThat(s.holdState()).isEqualTo("HELD"); assertThat(s.holdUntil().toInstant()).isEqualTo(later);
        assertThat(statuses.statuses(r.delivery().link().getContentId()).get(0).saleStatus()).isEqualTo("hold");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM artwork_reservation WHERE artwork_id=?", Integer.class, r.artwork())).isOne();
        assertThat((List<?>) requests.view(s).get("notices")).singleElement().satisfies(n -> assertThat((Map<String, Object>) n)
            .containsEntry("action", "EXTEND").containsEntry("reason", "고객 요청으로 기한을 연장했어요"));
        // The extended time is the one the expiry job honours.
        expiry.expire();
        assertThat(requests.mine(r.code(), r.session()).get(0).holdState()).isEqualTo("HELD");
        // A hold that has already run out cannot be extended back to life.
        jdbc.update("UPDATE artwork_request SET hold_until=? WHERE id=?", java.sql.Timestamp.from(Instant.now().minusSeconds(60)), s.id());
        final var lapsed = requests.mine(r.code(), r.session()).get(0);
        assertThatThrownBy(() -> requests.admin(lapsed.id(), new ArtworkRequestService.Command("EXTEND", lapsed.version(),
            UUID.randomUUID().toString(), Instant.now().plusSeconds(86400), "", false), "admin")).isInstanceOf(ResponseStatusException.class);
        expiry.expire();
        assertThat(requests.mine(r.code(), r.session()).get(0).holdState()).isEqualTo("EXPIRED");
    }
}
