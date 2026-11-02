package com.plink.ticket.service;

import com.plink.ticket.config.TicketProperties;
import com.plink.ticket.model.EventSession;
import com.plink.ticket.model.Presence;
import com.plink.ticket.model.Ticket;
import com.plink.ticket.repository.AdmissionRepository;
import com.plink.ticket.repository.InviteQuotaRepository;
import com.plink.ticket.repository.TicketRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A holder inviting others onto tickets of their own.
 *
 * <p>The organiser grants invites per tier, and may set a count of its own on any ticket
 * that was not itself invited, above or below its tier's. A holder who has registered, on a browser that
 * has proved it, issues up to that many tickets, each to an address they type; each is
 * mailed and claimed like any console-issued ticket, and remembers who invited it. An
 * invitee never invites in turn. Cancelling an invite deletes its ticket, which frees the
 * slot, and is allowed only until the invitee registers, and only as many times as the
 * ticket may invite: each invite and each cancel sends mail to an address the holder
 * typed, and a slot freed without limit would let them send it without end.
 *
 * <p>On the day, the event decides who sees whether the others in a group have come in:
 * nobody, the inviter alone, or the whole group. People in a group appear only by masked
 * address - never a seat, a reference or a full address.
 */
@Service
public class InviteService {
    public static final List<String> STATUSES = List.of("OFF", "INVITER", "GROUP");
    /** The most invites one tier, or one ticket, can be granted. */
    public static final int MAX_QUOTA = 100;

    private final TicketService tickets;
    private final TicketRepository ticketRows;
    private final InviteQuotaRepository quotas;
    private final AdmissionRepository admissions;
    private final TicketPasskeyService passkeys;
    private final EmailSender mail;
    private final TicketProperties properties;

    public InviteService(TicketService tickets, TicketRepository ticketRows, InviteQuotaRepository quotas,
            AdmissionRepository admissions, TicketPasskeyService passkeys, EmailSender mail,
            TicketProperties properties) {
        this.properties = properties;
        this.tickets = tickets;
        this.ticketRows = ticketRows;
        this.quotas = quotas;
        this.admissions = admissions;
        this.passkeys = passkeys;
        this.mail = mail;
    }

    /** The registered holder on a browser that has proved it, or a refusal saying which is missing. */
    private Ticket holder(TicketService.Resolved resolved, HttpSession session) {
        Ticket ticket = resolved.ticket;
        if (resolved.viaTransfer() || !ticket.claimed() || ticket.revoked()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "등록을 마친 입장권에서 볼 수 있어요.");
        }
        if (!passkeys.canView(session, ticket)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "패스키로 본인 확인을 먼저 해 주세요.");
        }
        return ticket;
    }

    /**
     * What this ticket may invite: nothing for an invitee, else the organiser's count for
     * this ticket when they set one, else its tier's grant.
     */
    public int quotaFor(Ticket ticket) {
        if (ticket.invitee) return 0;
        return ticket.inviteQuota != null ? ticket.inviteQuota : quotas.quota(ticket.sessionId, ticket.tier);
    }

    /** As {@link #quotaFor}, with the event's tier grants already read: for a page of tickets at once. */
    public static int quotaFor(Ticket ticket, Map<String, Integer> tierQuotas) {
        if (ticket.invitee) return 0;
        if (ticket.inviteQuota != null) return ticket.inviteQuota;
        return tierQuotas.getOrDefault(ticket.tier == null ? "" : ticket.tier, 0);
    }

    private static String address(Ticket ticket) {
        return ticket.holderEmail != null ? ticket.holderEmail : ticket.issuedToEmail;
    }

    // ---- From the console ----

    /**
     * Sets one ticket's own invite count, or with null returns it to its tier's. Lowering it
     * below what the holder has already used takes no invite back: those tickets stay, and
     * the holder simply has none left.
     */
    @Transactional
    public Map<String, Object> setQuota(long ticketId, Integer quota) {
        requireQuota(quota);
        Ticket ticket = ticketRows.lockById(ticketId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "입장권을 찾을 수 없어요."));
        if (ticket.invitee) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "초대받은 입장권은 다시 초대할 수 없어서 초대 인원을 정할 수 없어요.");
        }
        ticketRows.updateInviteQuota(ticket.id, quota);
        ticket.inviteQuota = quota;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ticketId", ticket.id);
        result.put("inviteQuota", quota);
        result.put("inviteQuotaEffective", quotaFor(ticket));
        result.put("invitesUsed", ticketRows.findInvitedBy(ticket.id).size());
        return result;
    }

    /**
     * The same for the tickets chosen in the console's list. Invitees, and tickets that are
     * not this event's, are skipped rather than failing the rest.
     */
    @Transactional
    public Map<String, Object> setQuotas(long sessionId, List<Long> ticketIds, Integer quota) {
        requireQuota(quota);
        int changed = 0, skipped = 0;
        for (long ticketId : ticketIds) {
            Ticket ticket = ticketRows.lockById(ticketId).filter(found -> found.sessionId == sessionId).orElse(null);
            if (ticket == null || ticket.invitee) { skipped++; continue; }
            ticketRows.updateInviteQuota(ticket.id, quota);
            changed++;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("changed", changed);
        result.put("skipped", skipped);
        return result;
    }

    /**
     * The group a ticket belongs to, for the console: its inviter and everyone they invited,
     * revoked ones too, with the inviter's invites and cancels counted against their quota.
     *
     * @param plain whether addresses are shown in full, as the console does for a test event
     */
    public Map<String, Object> consoleGroup(long ticketId, boolean plain) {
        Ticket ticket = ticketRows.findById(ticketId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "입장권을 찾을 수 없어요."));
        Ticket inviter = ticket.invitee
            ? ticket.invitedBy == null ? null : ticketRows.findById(ticket.invitedBy).orElse(null)
            : ticket;
        List<Ticket> members = new ArrayList<>();
        List<Ticket> invited = inviter == null ? List.of() : ticketRows.findInvitedBy(inviter.id);
        members.add(inviter == null ? ticket : inviter);
        members.addAll(invited);
        Map<Long, Presence> presence = admissions.findAll(members.stream().map(member -> member.id).toList());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Ticket member : members) {
            Presence p = presence.get(member.id);
            String state = p == null || p.entryCount == 0 ? "NOT_ENTERED" : p.inside() ? "INSIDE" : "OUTSIDE";
            java.sql.Timestamp since = "INSIDE".equals(state) ? p.insideSince
                : "OUTSIDE".equals(state) ? p.lastExitAt : null;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ticketId", member.id);
            row.put("ticketRef", member.ticketRef);
            row.put("role", inviter != null && member.id == inviter.id ? "INVITER" : "GUEST");
            row.put("current", member.id == ticket.id);
            row.put("email", plain ? address(member) : TicketService.mask(address(member)));
            row.put("name", member.invitee ? member.inviteName : null);
            row.put("tier", member.tier);
            row.put("registered", member.claimed());
            row.put("revoked", member.revoked());
            row.put("state", state);
            row.put("since", since == null ? null : since.toInstant().toString());
            row.put("sentAt", member.invitee && member.createdAt != null ? member.createdAt.toInstant().toString() : null);
            rows.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ticketId", ticket.id);
        result.put("inviterId", inviter == null ? null : inviter.id);
        result.put("name", ticket.invitee ? ticket.inviteName : null);
        result.put("members", rows);
        result.put("quotaEffective", inviter == null ? 0 : quotaFor(inviter));
        result.put("used", invited.size());
        result.put("cancelsUsed", inviter == null ? 0 : inviter.invitesCancelled);
        return result;
    }

    private static void requireQuota(Integer quota) {
        if (quota != null && (quota < 0 || quota > MAX_QUOTA)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "초대 인원은 0에서 " + MAX_QUOTA + "명 사이여야 해요.");
        }
    }

    // ---- From the holder's ticket ----

    /** The longest call name an inviter may give, in characters as a person counts them. */
    public static final int NAME_MAX = 12;
    /** How many times one invite's mail may be sent again, and how far apart. */
    public static final int RESEND_MAX = 3;
    public static final Duration RESEND_GAP = Duration.ofMinutes(10);

    /**
     * The group as this holder may see it.
     *
     * <p>{@code members} is one list: for the inviter, themselves first and then everyone
     * they invited, oldest first, revoked ones included so they can see what became of
     * them; for an invitee under GROUP, the inviter and the invitees who can still come;
     * otherwise the invitee alone. The inviter sees the addresses they typed in full; every
     * other address is masked. Where each one is ({@code state}) is said only when the
     * event lets this holder see it, and only from the event's day.
     */
    public Map<String, Object> view(TicketService.Resolved resolved, HttpSession session) {
        Ticket ticket = holder(resolved, session);
        EventSession event = tickets.requireSession(ticket.sessionId);
        int quota = quotaFor(ticket);
        List<Ticket> invited = ticketRows.findInvitedBy(ticket.id);
        int remaining = Math.max(0, quota - invited.size());
        Ticket inviter = ticket.invitee
            ? ticket.invitedBy == null ? null : ticketRows.findById(ticket.invitedBy).orElse(null)
            : ticket;
        // Read fresh: the cap must hold even on a ticket resolved before the last cancel.
        int cancelled = ticketRows.findById(ticket.id).map(row -> row.invitesCancelled).orElse(0);

        Instant now = Instant.now();
        LocalDate statesFrom = firstDay(event);
        boolean beforeDay = VenueDay.of(now, properties.venueZoneId(), event.dayCloseHour).isBefore(statesFrom);
        boolean states = !beforeDay && sees(event.inviteStatus, ticket);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("quota", quota);
        result.put("used", invited.size());
        result.put("remaining", remaining);
        result.put("canInvite", !ticket.invitee && remaining > 0);
        result.put("canCancel", !ticket.invitee && cancelled < quota);
        result.put("cancelsLeft", ticket.invitee ? 0 : Math.max(0, quota - cancelled));
        result.put("status", event.inviteStatus);
        result.put("invitedBy", ticket.invitee && inviter != null ? TicketService.mask(address(inviter)) : null);
        result.put("statesShown", states);
        result.put("statesFrom", statesFrom.toString());
        result.put("beforeDay", beforeDay);
        result.put("updatedAt", now.toString());
        result.put("members", members(ticket, inviter, invited, event.inviteStatus, states));
        return result;
    }

    /**
     * The venue day the event starts on - or its gates open on, when that is earlier: from
     * then on the group's movements mean something.
     */
    private LocalDate firstDay(EventSession event) {
        Instant first = event.startsAt.toInstant();
        if (event.gateOpensAt != null && event.gateOpensAt.toInstant().isBefore(first)) {
            first = event.gateOpensAt.toInstant();
        }
        return VenueDay.of(first, properties.venueZoneId(), event.dayCloseHour);
    }

    /** OFF shows nobody; INVITER shows the inviter their invitees; GROUP shows everyone in it. */
    private static boolean sees(String status, Ticket viewer) {
        return "GROUP".equals(status) || ("INVITER".equals(status) && !viewer.invitee);
    }

    private List<Map<String, Object>> members(Ticket viewer, Ticket inviter, List<Ticket> invited,
            String status, boolean states) {
        List<Ticket> members = new ArrayList<>();
        if (!viewer.invitee) {
            members.add(viewer);
            members.addAll(invited);
        } else if (inviter != null && "GROUP".equals(status)) {
            // Revoked tickets cannot come in, so they are not part of who is coming.
            members.add(inviter);
            members.addAll(ticketRows.findInvitedBy(inviter.id));
            members.removeIf(member -> member.revoked() && member.id != viewer.id);
        } else {
            members.add(viewer);
        }
        Map<Long, Presence> presence = states
            ? admissions.findAll(members.stream().map(member -> member.id).toList()) : Map.of();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Ticket member : members) rows.add(member(viewer, member, inviter, presence.get(member.id), states));
        return rows;
    }

    /** One person in the group as {@code viewer} sees them. */
    private static Map<String, Object> member(Ticket viewer, Ticket member, Ticket inviter, Presence p,
            boolean states) {
        boolean mine = !viewer.invitee && member.invitedBy != null && member.invitedBy == viewer.id;
        boolean pending = !member.claimed() && !member.revoked();
        String state = null;
        java.sql.Timestamp since = null;
        if (states) {
            state = p == null || p.entryCount == 0 ? "NOT_ENTERED" : p.inside() ? "INSIDE" : "OUTSIDE";
            since = "INSIDE".equals(state) ? p.insideSince : "OUTSIDE".equals(state) ? p.lastExitAt : null;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ticketId", mine ? member.id : null);
        row.put("role", inviter != null && member.id == inviter.id ? "INVITER" : "GUEST");
        row.put("me", member.id == viewer.id);
        row.put("name", member.invitee ? member.inviteName : null);
        // The inviter typed these addresses; nobody else is shown one in full.
        row.put("email", mine ? address(member) : TicketService.mask(address(member)));
        row.put("registered", member.claimed());
        row.put("revoked", member.revoked());
        row.put("state", state);
        row.put("since", since == null ? null : since.toInstant().toString());
        row.put("sentAt", mine && member.createdAt != null ? member.createdAt.toInstant().toString() : null);
        row.put("canResend", mine && pending && member.inviteResends < RESEND_MAX);
        row.put("resendsLeft", mine && pending ? Math.max(0, RESEND_MAX - member.inviteResends) : 0);
        return row;
    }

    /** Trimmed, null when empty, and refused past {@link #NAME_MAX} characters. */
    static String callName(Object raw) {
        if (raw == null) return null;
        String name = raw.toString().strip().replaceAll("\\s+", " ");
        if (name.isEmpty()) return null;
        if (name.codePoints().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "부를 이름에 쓸 수 없는 글자가 있어요.");
        }
        if (name.codePointCount(0, name.length()) > NAME_MAX) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "부를 이름은 " + NAME_MAX + "자까지 쓸 수 있어요.");
        }
        return name;
    }

    /** One of this holder's own invites, or not found: another holder's looks like no invite at all. */
    private Ticket ownInvite(Ticket holder, long ticketId) {
        return ticketRows.findById(ticketId)
            .filter(found -> !holder.invitee && found.invitedBy != null && found.invitedBy == holder.id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "초대를 찾을 수 없어요."));
    }

    /**
     * Names one of the holder's invitees, or with null goes back to the masked address.
     * Allowed whatever became of the invite, registered, inside or switched off.
     */
    public Map<String, Object> rename(TicketService.Resolved resolved, HttpSession session, long ticketId, Object name) {
        Ticket holder = holder(resolved, session);
        String callName = callName(name);
        Ticket invited = ownInvite(holder, ticketId);
        ticketRows.updateInviteName(invited.id, callName);
        return member(holder, ticketRows.findById(invited.id).orElseThrow(), holder, null, false);
    }

    /**
     * Mails an unregistered invite its link again - the same link. A few times at most, and
     * not again within {@link #RESEND_GAP}: each is mail to an address the holder typed.
     * Checked and counted in one statement, so two taps at once send one mail.
     */
    public Map<String, Object> resend(TicketService.Resolved resolved, HttpSession session, long ticketId) {
        Ticket holder = holder(resolved, session);
        Ticket invited = ownInvite(holder, ticketId);
        if (invited.claimed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 등록을 마친 초대예요.");
        }
        if (invited.revoked()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "운영팀이 비활성화한 초대는 다시 보낼 수 없어요.");
        }
        Instant now = Instant.now();
        if (!ticketRows.countInviteResent(invited.id, RESEND_MAX,
                java.sql.Timestamp.from(now.minus(RESEND_GAP)), java.sql.Timestamp.from(now))) {
            Ticket current = ticketRows.findById(invited.id).orElseThrow();
            if (current.inviteResends >= RESEND_MAX) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "초대 메일은 " + RESEND_MAX + "번까지 다시 보낼 수 있어요. 이미 모두 보냈어요.");
            }
            long wait = current.inviteResentAt == null ? 1 : Math.max(1, (long) Math.ceil(
                Duration.between(now, current.inviteResentAt.toInstant().plus(RESEND_GAP)).toSeconds() / 60.0));
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "조금 전에 다시 보냈어요. " + wait + "분 뒤에 다시 보낼 수 있어요.");
        }
        boolean mailed = tickets.resendInvite(invited, TicketService.mask(address(holder)));
        Map<String, Object> result = new LinkedHashMap<>(
            member(holder, ticketRows.findById(invited.id).orElseThrow(), holder, null, false));
        result.put("mailed", mailed);
        return result;
    }

    /**
     * Issues a ticket to the address the holder typed. Not transactional itself: the ticket
     * commits in the issuing path, with the check that allowed it, before the mail goes.
     */
    public Map<String, Object> invite(TicketService.Resolved resolved, HttpSession session, String email) {
        return invite(resolved, session, email, null);
    }

    /** As above, with what the holder will call them in the group, if anything. */
    public Map<String, Object> invite(TicketService.Resolved resolved, HttpSession session, String email, Object name) {
        Ticket ticket = holder(resolved, session);
        String callName = callName(name);
        if (ticket.invitee) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "초대받은 입장권으로는 다시 초대할 수 없어요.");
        }
        if (quotaFor(ticket) == 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "이 입장권에는 초대 권한이 없어요.");
        }
        String recipient = EmailOtpService.normalize(email);
        Map<String, Object> issued;
        try {
            issued = tickets.issueInvite(ticket.sessionId, recipient,
                new TicketService.Invitation(ticket.id, TicketService.mask(address(ticket)), callName,
                    () -> admit(ticket.id)));
        } catch (ResponseStatusException refused) {
            if (refused.getStatusCode().value() != HttpStatus.CONFLICT.value()) throw refused;
            // The console's wording speaks of deleting tickets, which a holder cannot do.
            String reason = refused.getReason();
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                reason != null && reason.contains("이미 있어요")
                    ? "이 주소로는 이미 이 이벤트의 입장권이 있어요." : reason);
        }
        long id = ((Number) issued.get("ticketId")).longValue();
        return member(ticket, ticketRows.findById(id).orElseThrow(), ticket, null, false);
    }

    /**
     * Inside the issuing transaction: the inviter's row is locked, so two taps at once
     * queue here and the second counts the first's ticket.
     */
    private void admit(long inviterId) {
        Ticket locked = ticketRows.lockById(inviterId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "입장권을 찾을 수 없어요."));
        if (locked.revoked() || !locked.claimed() || locked.invitee) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "이 입장권으로는 초대할 수 없어요.");
        }
        if (ticketRows.findInvitedBy(locked.id).size() >= quotaFor(locked)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "초대할 수 있는 인원을 모두 채웠어요.");
        }
    }

    /** Takes back an invite nobody has registered yet; its ticket is deleted and the slot is free again. */
    @Transactional
    public Map<String, Object> cancel(TicketService.Resolved resolved, HttpSession session, long ticketId) {
        Ticket holder = holder(resolved, session);
        // Locked first, as inviting does, so a cancel and an invite from two tabs queue.
        Ticket ticket = ticketRows.lockById(holder.id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "입장권을 찾을 수 없어요."));
        Ticket invited = ticketRows.lockById(ticketId)
            .filter(found -> found.invitedBy != null && found.invitedBy == ticket.id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "초대를 찾을 수 없어요."));
        if (invited.claimed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 등록을 마친 초대는 취소할 수 없어요.");
        }
        if (invited.revoked()) {
            // The organiser switched it off; whether that frees the slot is theirs to say.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "운영팀이 비활성화한 초대는 취소할 수 없어요.");
        }
        if (ticket.invitesCancelled >= quotaFor(ticket)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "초대를 더 취소할 수 없어요. 취소할 수 있는 횟수를 모두 썼어요.");
        }
        tickets.delete(invited.id, false);
        ticketRows.countInviteCancelled(ticket.id);
        String recipient = address(invited);
        String eventName = tickets.requireSession(invited.sessionId).name;
        AfterCommit.run(() -> mail.send(recipient, "[" + eventName + "] 초대가 취소되었어요",
            TicketService.mask(address(ticket)) + "님이 초대를 취소했어요. 받은 입장권 링크는 더 이상 사용할 수 없어요."));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cancelled", true);
        result.put("ticketId", invited.id);
        return result;
    }
}
