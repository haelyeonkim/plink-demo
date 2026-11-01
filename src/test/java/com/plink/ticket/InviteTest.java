package com.plink.ticket;

import com.plink.account.AdminAccountRepository;
import com.plink.account.AdminAccountService;
import com.plink.ticket.model.Ticket;
import com.plink.ticket.repository.EventSessionRepository;
import com.plink.ticket.repository.HolderRepository;
import com.plink.ticket.repository.InviteQuotaRepository;
import com.plink.ticket.repository.TicketRepository;
import com.plink.ticket.service.InviteService;
import com.plink.ticket.service.Secrets;
import com.plink.ticket.service.TicketPasskeyService;
import com.plink.ticket.service.TicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A holder inviting others: who may, how many, and who sees whether the group has come in. */
// Isolated from ./.env: the suite must not depend on a developer's local configuration.
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:invitetest;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.config.import=", "plink.admin.email=", "plink.admin.password=", "plink.auth.base-url=http://localhost:3000"})
@AutoConfigureMockMvc
@Import(RecordingEmail.class)
class InviteTest {
    @Autowired InviteService invites;
    @Autowired InviteQuotaRepository quotas;
    @Autowired TicketService tickets;
    @Autowired TicketRepository ticketRows;
    @Autowired TicketPasskeyService passkeys;
    @Autowired HolderRepository holders;
    @Autowired EventSessionRepository sessions;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired RecordingEmail.Mailbox mailbox;
    @Autowired AdminAccountService accounts;
    @Autowired AdminAccountRepository accountRows;

    private static final String PASSWORD = "a-long-enough-password";

    @BeforeEach void clearMail() { mailbox.clear(); }

    private long newSession(Map<String, Integer> quota) {
        long sessionId = sessions.insert("초대 이벤트 " + Secrets.randomAlnum(6), "메인홀",
            Timestamp.from(Instant.now().plus(1, ChronoUnit.HOURS)), null);
        quotas.replace(sessionId, quota);
        return sessionId;
    }

    private record Holder(TicketService.Resolved resolved, MockHttpSession session, long ticketId, String token) {
        String base(long sessionId) { return "/api/tickets/" + sessionId + "/" + token + "/invites"; }
    }

    private static String tokenOf(String url) { return url.substring(url.lastIndexOf('/') + 1); }

    /** Registers a ticket from its link, and proves it on a fresh browser. */
    private Holder register(long sessionId, String token, String email) {
        long ticketId = tickets.resolve(sessionId, token).ticket.id;
        long holderId = holders.findByEmail(email).map(h -> h.id)
            .orElseGet(() -> holders.create(email, tickets.userHandleFor(email)));
        ticketRows.bind(ticketId, holderId, email);
        MockHttpSession session = new MockHttpSession();
        passkeys.markViewer(session, ticketRows.findById(ticketId).orElseThrow());
        return new Holder(tickets.resolve(sessionId, token), session, ticketId, token);
    }

    private Holder holder(long sessionId, String email, String tier) {
        Map<String, Object> issued = tickets.issue(sessionId, email, null, tier, null);
        return register(sessionId, tokenOf(issued.get("url").toString()), email);
    }

    private long invite(Holder inviter, String email) {
        return ((Number) invites.invite(inviter.resolved(), inviter.session(), email).get("ticketId")).longValue();
    }

    /** The invitee registering the ticket they were mailed. */
    private Holder accept(long sessionId, String email) {
        return register(sessionId, tokenOf(mailbox.ticketUrlFor(email).orElseThrow()), email);
    }

    private static HttpStatus outcome(Runnable call) {
        try { call.run(); return HttpStatus.OK; }
        catch (ResponseStatusException e) { return HttpStatus.valueOf(e.getStatusCode().value()); }
    }

    private Map<String, Object> view(Holder h) { return invites.view(h.resolved(), h.session()); }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> group(Map<String, Object> view) {
        return (List<Map<String, Object>>) view.get("group");
    }

    @Test void theQuotaComesFromTheTierAndBlankTierReadsTheEmptyKey() {
        long sessionId = newSession(Map.of("VIP", 2, "", 1));
        Holder vip = holder(sessionId, "vip@example.com", "VIP");
        Holder plain = holder(sessionId, "plain@example.com", null);
        Holder general = holder(sessionId, "general@example.com", "일반");

        assertEquals(2, view(vip).get("quota"));
        assertEquals(true, view(vip).get("canInvite"));
        assertEquals(1, view(plain).get("quota"));
        assertEquals(0, view(general).get("quota"));
        assertEquals(false, view(general).get("canInvite"));
        assertEquals(HttpStatus.FORBIDDEN, outcome(() -> invite(general, "nobody@example.com")));

        invite(vip, "guest-one@example.com");
        Map<String, Object> after = view(vip);
        assertEquals(1, after.get("used"));
        assertEquals(1, after.get("remaining"));
        String[] mail = mailbox.sent.get(mailbox.sent.size() - 1);
        assertEquals("guest-one@example.com", mail[0]);
        assertTrue(mail[1].endsWith("초대 입장권이 도착했어요"));
        assertTrue(mail[2].contains("v***@example.com님이 초대했어요"));
        assertTrue(mail[2].contains("/tickets/" + sessionId + "/"));
        Ticket issued = ticketRows.findById(((Number) ((Map<?, ?>) ((List<?>) after.get("invites")).get(0))
            .get("ticketId")).longValue()).orElseThrow();
        assertEquals(vip.ticketId(), issued.invitedBy);
        assertTrue(issued.invitee);
        assertEquals("ISSUED", issued.status);
    }

    @Test void anInviteeCannotInviteAndSeesWhoInvitedThem() {
        long sessionId = newSession(Map.of("", 3));
        Holder inviter = holder(sessionId, "host@example.com", null);
        invite(inviter, "friend@example.com");
        Holder friend = accept(sessionId, "friend@example.com");

        Map<String, Object> mine = view(friend);
        assertEquals(0, mine.get("quota"), "an invitee's ticket has no tier, and invites nobody whatever '' grants");
        assertEquals(false, mine.get("canInvite"));
        assertEquals("h***@example.com", mine.get("invitedBy"));
        assertEquals(HttpStatus.FORBIDDEN, outcome(() -> invite(friend, "another@example.com")));
    }

    @Test void anUnprovenBrowserOrUnregisteredTicketIsRefused() throws Exception {
        long sessionId = newSession(Map.of("", 2));
        Holder inviter = holder(sessionId, "proven@example.com", null);
        MockHttpSession stranger = new MockHttpSession();
        mvc.perform(get(inviter.base(sessionId)).session(stranger)).andExpect(status().isForbidden());
        mvc.perform(post(inviter.base(sessionId)).session(stranger).with(csrf())
                .contentType("application/json").content("{\"email\":\"x@example.com\"}"))
            .andExpect(status().isForbidden());
        // CSRF holds for a proven browser too.
        mvc.perform(post(inviter.base(sessionId)).session(inviter.session())
                .contentType("application/json").content("{\"email\":\"x@example.com\"}"))
            .andExpect(status().isForbidden());
        assertTrue(ticketRows.findInvitedBy(inviter.ticketId()).isEmpty());

        Map<String, Object> issued = tickets.issue(sessionId, "unclaimed@example.com", null, null, null);
        TicketService.Resolved unclaimed = tickets.resolve(sessionId, tokenOf(issued.get("url").toString()));
        assertEquals(HttpStatus.FORBIDDEN, outcome(() -> invites.view(unclaimed, new MockHttpSession())));
        assertEquals(HttpStatus.FORBIDDEN, outcome(() -> invites.invite(unclaimed, new MockHttpSession(), "y@example.com")));

        mvc.perform(post(inviter.base(sessionId)).session(inviter.session()).with(csrf())
                .contentType("application/json").content("{\"email\":\"Over-MVC@Example.com\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value("o***@example.com"))
            .andExpect(jsonPath("$.registered").value(false));
    }

    @Test void anExhaustedQuotaIsRefusedAndTwoTapsAtOnceGiveOneTicket() throws Exception {
        long sessionId = newSession(Map.of("", 1));
        Holder inviter = holder(sessionId, "racer@example.com", null);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<HttpStatus>> taps = new ArrayList<>();
        for (String email : List.of("first-tap@example.com", "second-tap@example.com")) {
            taps.add(pool.submit(() -> { go.await(); return outcome(() -> invite(inviter, email)); }));
        }
        go.countDown();
        List<HttpStatus> results = new ArrayList<>();
        for (Future<HttpStatus> tap : taps) results.add(tap.get());
        pool.shutdown();
        assertEquals(1, results.stream().filter(HttpStatus.OK::equals).count(), results.toString());
        assertTrue(results.contains(HttpStatus.CONFLICT), results.toString());
        assertEquals(1, ticketRows.findInvitedBy(inviter.ticketId()).size());

        assertEquals(HttpStatus.CONFLICT, outcome(() -> invite(inviter, "third@example.com")));
        assertEquals(0, view(inviter).get("remaining"));
    }

    @Test void anAddressWithATicketAlreadyIsAConflict() {
        long sessionId = newSession(Map.of("", 2));
        Holder inviter = holder(sessionId, "self@example.com", null);
        assertEquals(HttpStatus.CONFLICT, outcome(() -> invite(inviter, "SELF@example.com")));
        assertEquals(HttpStatus.BAD_REQUEST, outcome(() -> invite(inviter, "not-an-address")));
        assertEquals(2, view(inviter).get("remaining"));
    }

    @Test void onlyTheInviterCancelsAndOnlyBeforeRegistration() {
        long sessionId = newSession(Map.of("", 2));
        Holder inviter = holder(sessionId, "canceller@example.com", null);
        Holder other = holder(sessionId, "other-host@example.com", null);
        long pending = invite(inviter, "pending@example.com");
        invite(inviter, "joined@example.com");
        Holder joined = accept(sessionId, "joined@example.com");

        assertEquals(HttpStatus.NOT_FOUND, outcome(() -> invites.cancel(other.resolved(), other.session(), pending)));
        assertEquals(HttpStatus.NOT_FOUND, outcome(() -> invites.cancel(joined.resolved(), joined.session(), pending)));
        assertEquals(HttpStatus.CONFLICT,
            outcome(() -> invites.cancel(inviter.resolved(), inviter.session(), joined.ticketId())));
        assertTrue(ticketRows.findById(joined.ticketId()).isPresent());

        mailbox.clear();
        invites.cancel(inviter.resolved(), inviter.session(), pending);
        assertTrue(ticketRows.findById(pending).isEmpty());
        assertEquals("pending@example.com", mailbox.sent.get(0)[0]);
        assertEquals(1, view(inviter).get("remaining"), "the cancelled invite frees its slot");
        assertEquals(HttpStatus.OK, outcome(() -> invite(inviter, "pending@example.com")));
    }

    @Test void cancellingIsCappedSoInvitingCannotLoopMail() {
        long sessionId = newSession(Map.of("", 1));
        Holder inviter = holder(sessionId, "looper@example.com", null);
        long first = invite(inviter, "loop@example.com");
        invites.cancel(inviter.resolved(), inviter.session(), first);
        long second = invite(inviter, "loop@example.com");
        assertEquals(false, view(inviter).get("canCancel"));
        assertEquals(HttpStatus.CONFLICT, outcome(() -> invites.cancel(inviter.resolved(), inviter.session(), second)));
        assertTrue(ticketRows.findById(second).isPresent());
    }

    @Test void whoSeesTheGroupFollowsTheEventSetting() {
        long sessionId = newSession(Map.of("", 2));
        Holder inviter = holder(sessionId, "leader@example.com", null);
        invite(inviter, "inside-guest@example.com");
        long waiting = invite(inviter, "waiting@example.com");
        Holder guest = accept(sessionId, "inside-guest@example.com");
        Timestamp since = Timestamp.from(Instant.now().minus(5, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS));
        jdbc.update("UPDATE ticket_presence SET state = 'INSIDE', entry_count = 1, inside_since = ? WHERE ticket_id = ?",
            since, guest.ticketId());
        jdbc.update("UPDATE ticket_presence SET state = 'OUTSIDE', entry_count = 1, last_exit_at = ? WHERE ticket_id = ?",
            since, inviter.ticketId());

        // GROUP is the default: everyone in it sees everyone.
        List<Map<String, Object>> seen = group(view(guest));
        assertEquals(3, seen.size());
        Map<String, Object> leader = seen.get(0);
        assertEquals("l***@example.com", leader.get("email"));
        assertEquals("INVITER", leader.get("role"));
        assertEquals("OUTSIDE", leader.get("state"));
        assertEquals(since.toInstant().toString(), leader.get("since"));
        Map<String, Object> me = seen.stream().filter(row -> Boolean.TRUE.equals(row.get("me"))).findFirst().orElseThrow();
        assertEquals("GUEST", me.get("role"));
        assertEquals("INSIDE", me.get("state"));
        assertEquals("NOT_ENTERED", seen.get(2).get("state"));
        assertNull(seen.get(2).get("since"));
        assertEquals(3, group(view(inviter)).size());

        sessions.updateInviteStatus(sessionId, "INVITER");
        assertEquals(3, group(view(inviter)).size());
        assertNull(view(guest).get("group"));
        assertEquals("INVITER", view(guest).get("status"));

        sessions.updateInviteStatus(sessionId, "OFF");
        assertNull(view(inviter).get("group"));
        assertNull(view(guest).get("group"));
        assertEquals(2, ((List<?>) view(inviter).get("invites")).size(), "the inviter still manages their invites");

        // A revoked invitee drops out of who is coming.
        sessions.updateInviteStatus(sessionId, "GROUP");
        tickets.setRevoked(waiting, true);
        assertEquals(2, group(view(inviter)).size());
    }

    @Test void holdersSeeMaskedAddressesOnly() throws Exception {
        long sessionId = newSession(Map.of("", 1));
        Holder inviter = holder(sessionId, "masked-host@example.com", null);
        jdbc.update("UPDATE ticket SET seat = 'Z-99' WHERE id = ?", inviter.ticketId());
        invite(inviter, "masked-guest@example.com");
        Holder guest = accept(sessionId, "masked-guest@example.com");
        String hostRef = ticketRows.findById(inviter.ticketId()).orElseThrow().ticketRef;
        String guestRef = ticketRows.findById(guest.ticketId()).orElseThrow().ticketRef;

        for (Holder h : List.of(inviter, guest)) {
            String body = mvc.perform(get(h.base(sessionId)).session(h.session()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertFalse(body.contains("masked-host@"), body);
            assertFalse(body.contains("masked-guest@"), body);
            assertFalse(body.contains(hostRef), body);
            assertFalse(body.contains(guestRef), body);
            assertFalse(body.contains("Z-99"), body);
            assertTrue(body.contains("m***@example.com"), body);
        }
    }

    @Test void deletingTheInviterKeepsTheInviteeWhoStillCannotInvite() {
        long sessionId = newSession(Map.of("", 2));
        Holder inviter = holder(sessionId, "deleted-host@example.com", null);
        invite(inviter, "survivor@example.com");
        Holder survivor = accept(sessionId, "survivor@example.com");

        tickets.delete(inviter.ticketId(), true);
        Ticket kept = ticketRows.findById(survivor.ticketId()).orElseThrow();
        assertNull(kept.invitedBy);
        assertTrue(kept.invitee);
        Map<String, Object> mine = invites.view(tickets.resolve(sessionId, survivor.token()), survivor.session());
        assertNull(mine.get("invitedBy"));
        assertNull(mine.get("group"));
        assertEquals(0, mine.get("quota"));

        // The whole event, invites and quotas included, still goes in one delete.
        invite(holder(sessionId, "second-host@example.com", null), "second-guest@example.com");
        assertEquals(1, sessions.delete(sessionId));
        assertTrue(quotas.findBySession(sessionId).isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ticket WHERE session_id = ?", Integer.class, sessionId));
    }

    @Test void theConsoleSavesTheSettingAndListsWhoInvitedWhom() throws Exception {
        long sessionId = newSession(Map.of());
        MockHttpSession organiser = signIn("invite-organiser@example.com");
        mvc.perform(get("/api/admin/sessions").session(organiser))
            .andExpect(status().isOk());
        String policy = "/api/admin/sessions/" + sessionId + "/policy";
        mvc.perform(put(policy).session(organiser).with(csrf()).contentType("application/json")
                .content("{\"inviteStatus\":\"inviter\",\"inviteQuotas\":{\"VIP\":3,\"\":1,\"일반\":0}}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.inviteStatus").value("INVITER"))
            .andExpect(jsonPath("$.inviteQuotas.VIP").value(3))
            .andExpect(jsonPath("$.inviteQuotas['']").value(1))
            .andExpect(jsonPath("$.inviteQuotas['일반']").doesNotExist());

        // A bad value anywhere writes nothing, the status included.
        mvc.perform(put(policy).session(organiser).with(csrf()).contentType("application/json")
                .content("{\"inviteStatus\":\"GROUP\",\"inviteQuotas\":{\"VIP\":101}}"))
            .andExpect(status().isBadRequest());
        mvc.perform(put(policy).session(organiser).with(csrf()).contentType("application/json")
                .content("{\"inviteStatus\":\"EVERYONE\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(put(policy).session(organiser).with(csrf()).contentType("application/json")
                .content("{\"inviteQuotas\":{\"VIP\":-1}}"))
            .andExpect(status().isBadRequest());
        assertEquals("INVITER", sessions.findById(sessionId).orElseThrow().inviteStatus);
        assertEquals(Map.of("VIP", 3, "", 1), quotas.findBySession(sessionId));

        // A save that leaves the invite keys out leaves them as they were.
        mvc.perform(put(policy).session(organiser).with(csrf()).contentType("application/json")
                .content("{\"reentryMode\":\"UNLIMITED\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.inviteQuotas.VIP").value(3));

        Holder inviter = holder(sessionId, "listed-host@example.com", "VIP");
        long guest = invite(inviter, "listed-guest@example.com");
        String ref = ticketRows.findById(inviter.ticketId()).orElseThrow().ticketRef;
        mvc.perform(get("/api/admin/sessions/" + sessionId + "/tickets?q=listed-guest").session(organiser))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].ticketId").value(guest))
            .andExpect(jsonPath("$.items[0].invitee").value(true))
            .andExpect(jsonPath("$.items[0].invitedBy.ticketId").value(inviter.ticketId()))
            .andExpect(jsonPath("$.items[0].invitedBy.ticketRef").value(ref));
        mvc.perform(get("/api/admin/sessions/" + sessionId + "/tickets?q=listed-host").session(organiser))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].invitesUsed").value(1))
            .andExpect(jsonPath("$.items[0].invitedBy").value(org.hamcrest.Matchers.nullValue()));

        // The holder's own cancel goes through the API, with CSRF.
        mvc.perform(delete(inviter.base(sessionId) + "/" + guest).session(inviter.session()).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.cancelled").value(true));
    }

    /** The holder as a new request would see them: the console may have changed their ticket since. */
    private Holder fresh(Holder h) {
        return new Holder(tickets.resolve(h.resolved().ticket.sessionId, h.token()), h.session(), h.ticketId(), h.token());
    }

    private String quotaUrl(long ticketId) { return "/api/admin/tickets/" + ticketId + "/invite-quota"; }

    private String bulkQuotaUrl(long sessionId) { return "/api/admin/sessions/" + sessionId + "/tickets/invite-quota"; }

    private org.springframework.test.web.servlet.ResultActions setQuota(MockHttpSession organiser, long ticketId,
            String body) throws Exception {
        return mvc.perform(put(quotaUrl(ticketId)).session(organiser).with(csrf())
            .contentType("application/json").content(body));
    }

    @Test void aTicketsOwnCountOverridesItsTierBothWays() throws Exception {
        long sessionId = newSession(Map.of("VIP", 2));
        MockHttpSession organiser = signIn("override-organiser@example.com");
        Holder general = holder(sessionId, "override-general@example.com", "일반");
        Holder vip = holder(sessionId, "override-vip@example.com", "VIP");
        assertEquals(0, view(general).get("quota"));

        // Above a tier that grants none.
        setQuota(organiser, general.ticketId(), "{\"quota\":3}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.inviteQuota").value(3))
            .andExpect(jsonPath("$.inviteQuotaEffective").value(3));
        Holder low = fresh(general);
        assertEquals(3, view(low).get("quota"));
        assertEquals(true, view(low).get("canInvite"));
        assertEquals(HttpStatus.OK, outcome(() -> invite(low, "low-tier-guest@example.com")));
        assertEquals(HttpStatus.OK, outcome(() -> invite(fresh(vip), "first-of-vip@example.com")));

        // Zero stops a tier that grants some.
        setQuota(organiser, vip.ticketId(), "{\"quota\":0}").andExpect(status().isOk())
            .andExpect(jsonPath("$.inviteQuotaEffective").value(0));
        Holder stopped = fresh(vip);
        assertEquals(0, view(stopped).get("quota"));
        assertEquals(false, view(stopped).get("canInvite"));
        assertEquals(HttpStatus.FORBIDDEN, outcome(() -> invite(stopped, "blocked@example.com")));

        // Null goes back to the tier.
        setQuota(organiser, vip.ticketId(), "{\"quota\":null}").andExpect(status().isOk())
            .andExpect(jsonPath("$.inviteQuota").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.inviteQuotaEffective").value(2));
        assertNull(ticketRows.findById(vip.ticketId()).orElseThrow().inviteQuota);
        assertEquals(2, view(fresh(vip)).get("quota"));
        assertEquals(1, view(fresh(vip)).get("remaining"));

        // The list says which is which.
        mvc.perform(get("/api/admin/sessions/" + sessionId + "/tickets?q=override-general").session(organiser))
            .andExpect(jsonPath("$.items[0].inviteQuota").value(3))
            .andExpect(jsonPath("$.items[0].inviteQuotaEffective").value(3))
            .andExpect(jsonPath("$.items[0].invitesUsed").value(1));
        mvc.perform(get("/api/admin/sessions/" + sessionId + "/tickets?q=override-vip").session(organiser))
            .andExpect(jsonPath("$.items[0].inviteQuota").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.items[0].inviteQuotaEffective").value(2));
        mvc.perform(get("/api/admin/sessions/" + sessionId + "/tickets?q=low-tier-guest").session(organiser))
            .andExpect(jsonPath("$.items[0].inviteQuotaEffective").value(0));
        assertEquals(1, ticketRows.countInviteOverrides(sessionId));
    }

    @Test void anInviteeCannotBeGivenACount() throws Exception {
        long sessionId = newSession(Map.of("", 1));
        MockHttpSession organiser = signIn("override-organiser@example.com");
        Holder inviter = holder(sessionId, "override-host@example.com", null);
        long guest = invite(inviter, "override-guest@example.com");
        setQuota(organiser, guest, "{\"quota\":2}").andExpect(status().isConflict());
        setQuota(organiser, guest, "{\"quota\":null}").andExpect(status().isConflict());
        assertNull(ticketRows.findById(guest).orElseThrow().inviteQuota);

        // Chosen in bulk, the invitee is skipped and the rest change.
        mvc.perform(post(bulkQuotaUrl(sessionId)).session(organiser).with(csrf())
                .contentType("application/json")
                .content("{\"ticketIds\":[" + guest + "," + inviter.ticketId() + "],\"quota\":4}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.changed").value(1))
            .andExpect(jsonPath("$.skipped").value(1));
        assertNull(ticketRows.findById(guest).orElseThrow().inviteQuota);
        assertEquals(4, ticketRows.findById(inviter.ticketId()).orElseThrow().inviteQuota);
        assertEquals(0, invites.quotaFor(ticketRows.findById(guest).orElseThrow()));
    }

    @Test void loweringBelowWhatIsUsedKeepsTheInvites() throws Exception {
        long sessionId = newSession(Map.of("", 3));
        MockHttpSession organiser = signIn("override-organiser@example.com");
        Holder inviter = holder(sessionId, "lowered@example.com", null);
        long one = invite(inviter, "lowered-one@example.com");
        long two = invite(inviter, "lowered-two@example.com");
        setQuota(organiser, inviter.ticketId(), "{\"quota\":1}").andExpect(status().isOk())
            .andExpect(jsonPath("$.invitesUsed").value(2));
        Holder now = fresh(inviter);
        assertTrue(ticketRows.findById(one).isPresent());
        assertTrue(ticketRows.findById(two).isPresent());
        assertEquals(2, view(now).get("used"));
        assertEquals(0, view(now).get("remaining"));
        assertEquals(false, view(now).get("canInvite"));
        assertEquals(HttpStatus.CONFLICT, outcome(() -> invite(now, "lowered-three@example.com")));
    }

    @Test void theCancelCapFollowsTheTicketsOwnCount() throws Exception {
        long sessionId = newSession(Map.of());
        MockHttpSession organiser = signIn("override-organiser@example.com");
        Holder inviter = holder(sessionId, "cap-host@example.com", null);
        setQuota(organiser, inviter.ticketId(), "{\"quota\":2}").andExpect(status().isOk());
        Holder h = fresh(inviter);
        invites.cancel(h.resolved(), h.session(), invite(h, "cap-one@example.com"));
        invites.cancel(h.resolved(), h.session(), invite(h, "cap-two@example.com"));
        long third = invite(h, "cap-three@example.com");
        assertEquals(false, view(h).get("canCancel"));
        assertEquals(HttpStatus.CONFLICT, outcome(() -> invites.cancel(h.resolved(), h.session(), third)));

        // Raised, the cap rises with it.
        setQuota(organiser, inviter.ticketId(), "{\"quota\":3}").andExpect(status().isOk());
        Holder raised = fresh(inviter);
        assertEquals(true, view(raised).get("canCancel"));
        assertEquals(HttpStatus.OK, outcome(() -> invites.cancel(raised.resolved(), raised.session(), third)));
    }

    @Test void theCountIsCheckedAndOnlyTicketAccountsMaySetIt() throws Exception {
        long sessionId = newSession(Map.of());
        Holder holder = holder(sessionId, "guarded@example.com", null);
        MockHttpSession organiser = signIn("override-organiser@example.com");
        for (String bad : List.of("{\"quota\":101}", "{\"quota\":-1}", "{\"quota\":1.5}",
                "{\"quota\":\"3\"}", "{}")) {
            setQuota(organiser, holder.ticketId(), bad).andExpect(status().isBadRequest());
        }
        mvc.perform(post(bulkQuotaUrl(sessionId)).session(organiser).with(csrf())
                .contentType("application/json").content("{\"ticketIds\":[" + holder.ticketId() + "],\"quota\":101}"))
            .andExpect(status().isBadRequest());
        assertNull(ticketRows.findById(holder.ticketId()).orElseThrow().inviteQuota);
        setQuota(organiser, holder.ticketId(), "{\"quota\":100}").andExpect(status().isOk());
        setQuota(organiser, 99_999_999L, "{\"quota\":1}").andExpect(status().isNotFound());

        // A ticket of another event is not changed through that event.
        long otherSession = newSession(Map.of());
        mvc.perform(post(bulkQuotaUrl(otherSession)).session(organiser).with(csrf())
                .contentType("application/json").content("{\"ticketIds\":[" + holder.ticketId() + "],\"quota\":5}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.changed").value(0))
            .andExpect(jsonPath("$.skipped").value(1));

        // Signed out, without CSRF, signed in to an account that may not manage tickets, or
        // as the holder: refused, and nothing changes.
        mvc.perform(put(quotaUrl(holder.ticketId())).with(csrf()).contentType("application/json")
                .content("{\"quota\":5}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(put(quotaUrl(holder.ticketId())).session(organiser).contentType("application/json")
                .content("{\"quota\":5}"))
            .andExpect(status().isForbidden());
        MockHttpSession linksOnly = signIn("links-only-organiser@example.com", false);
        mvc.perform(put(quotaUrl(holder.ticketId())).session(linksOnly).with(csrf()).contentType("application/json")
                .content("{\"quota\":5}"))
            .andExpect(status().isForbidden());
        mvc.perform(post(bulkQuotaUrl(sessionId)).session(linksOnly).with(csrf())
                .contentType("application/json").content("{\"ticketIds\":[" + holder.ticketId() + "],\"quota\":5}"))
            .andExpect(status().isForbidden());
        mvc.perform(put(quotaUrl(holder.ticketId())).session(holder.session()).with(csrf())
                .contentType("application/json").content("{\"quota\":5}"))
            .andExpect(status().isUnauthorized());
        assertEquals(100, ticketRows.findById(holder.ticketId()).orElseThrow().inviteQuota);
    }

    private MockHttpSession signIn(String email) throws Exception { return signIn(email, true); }

    private MockHttpSession signIn(String email, boolean canTickets) throws Exception {
        accountRows.findByEmail(email).orElseGet(() -> {
            accounts.create(email, PASSWORD, null, true, canTickets, false);
            return accountRows.findByEmail(email).orElseThrow();
        });
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).with(csrf())
                .contentType("application/json")
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk());
        return session;
    }
}
