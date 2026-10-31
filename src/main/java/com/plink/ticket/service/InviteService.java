package com.plink.ticket.service;

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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A holder inviting others onto tickets of their own.
 *
 * <p>The organiser grants invites per tier. A holder who has registered, on a browser that
 * has proved it, issues up to that many tickets, each to an address they type; each is
 * mailed and claimed like any console-issued ticket, and remembers who invited it. An
 * invitee never invites in turn. Cancelling an invite deletes its ticket, which frees the
 * slot, and is allowed only until the invitee registers.
 *
 * <p>On the day, the event decides who sees whether the others in a group have come in:
 * nobody, the inviter alone, or the whole group. People in a group appear only by masked
 * address - never a seat, a reference or a full address.
 */
@Service
public class InviteService {
    public static final List<String> STATUSES = List.of("OFF", "INVITER", "GROUP");
    /** The most invites one tier can be granted. */
    public static final int MAX_QUOTA = 100;

    private final TicketService tickets;
    private final TicketRepository ticketRows;
    private final InviteQuotaRepository quotas;
    private final AdmissionRepository admissions;
    private final TicketPasskeyService passkeys;
    private final EmailSender mail;

    public InviteService(TicketService tickets, TicketRepository ticketRows, InviteQuotaRepository quotas,
            AdmissionRepository admissions, TicketPasskeyService passkeys, EmailSender mail) {
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

    /** What this ticket may invite: its tier's grant, and nothing for an invitee. */
    public int quotaFor(Ticket ticket) {
        return ticket.invitee ? 0 : quotas.quota(ticket.sessionId, ticket.tier);
    }

    private static String address(Ticket ticket) {
        return ticket.holderEmail != null ? ticket.holderEmail : ticket.issuedToEmail;
    }

    // ---- From the holder's ticket ----

    public Map<String, Object> view(TicketService.Resolved resolved, HttpSession session) {
        Ticket ticket = holder(resolved, session);
        EventSession event = tickets.requireSession(ticket.sessionId);
        int quota = quotaFor(ticket);
        List<Ticket> invited = ticketRows.findInvitedBy(ticket.id);
        int remaining = Math.max(0, quota - invited.size());
        Ticket inviter = ticket.invitee
            ? ticket.invitedBy == null ? null : ticketRows.findById(ticket.invitedBy).orElse(null)
            : ticket;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("quota", quota);
        result.put("used", invited.size());
        result.put("remaining", remaining);
        result.put("canInvite", !ticket.invitee && remaining > 0);
        result.put("status", event.inviteStatus);
        result.put("invitedBy", ticket.invitee && inviter != null ? TicketService.mask(address(inviter)) : null);
        result.put("invites", invited.stream().map(InviteService::inviteRow).toList());
        result.put("group", sees(event.inviteStatus, ticket) && inviter != null ? group(inviter, ticket) : null);
        return result;
    }

    /** OFF shows nobody; INVITER shows the inviter their invitees; GROUP shows everyone in it. */
    private static boolean sees(String status, Ticket viewer) {
        return "GROUP".equals(status) || ("INVITER".equals(status) && !viewer.invitee);
    }

    /**
     * The inviter and the tickets they invited, where each one is now. Revoked tickets are
     * left out: they cannot come in, so they are not part of who is coming. Null while
     * nobody has been invited, as there is no group to show.
     */
    private List<Map<String, Object>> group(Ticket inviter, Ticket viewer) {
        List<Ticket> members = new ArrayList<>();
        members.add(inviter);
        members.addAll(ticketRows.findInvitedBy(inviter.id));
        members.removeIf(Ticket::revoked);
        if (members.size() < 2 || members.stream().noneMatch(member -> member.id == viewer.id)) return null;
        Map<Long, Presence> presence = admissions.findAll(members.stream().map(member -> member.id).toList());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Ticket member : members) {
            Presence p = presence.get(member.id);
            String state = p == null || p.entryCount == 0 ? "NOT_ENTERED" : p.inside() ? "INSIDE" : "OUTSIDE";
            java.sql.Timestamp since = "INSIDE".equals(state) ? p.insideSince
                : "OUTSIDE".equals(state) ? p.lastExitAt : null;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("email", TicketService.mask(address(member)));
            row.put("role", member.id == inviter.id ? "INVITER" : "GUEST");
            row.put("me", member.id == viewer.id);
            row.put("state", state);
            row.put("since", since == null ? null : since.toInstant().toString());
            rows.add(row);
        }
        return rows;
    }

    private static Map<String, Object> inviteRow(Ticket invited) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ticketId", invited.id);
        row.put("email", TicketService.mask(address(invited)));
        row.put("registered", invited.claimed());
        row.put("revoked", invited.revoked());
        return row;
    }

    /**
     * Issues a ticket to the address the holder typed. Not transactional itself: the ticket
     * commits in the issuing path, with the check that allowed it, before the mail goes.
     */
    public Map<String, Object> invite(TicketService.Resolved resolved, HttpSession session, String email) {
        Ticket ticket = holder(resolved, session);
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
                new TicketService.Invitation(ticket.id, TicketService.mask(address(ticket)), () -> admit(ticket.id)));
        } catch (ResponseStatusException refused) {
            if (refused.getStatusCode().value() != HttpStatus.CONFLICT.value()) throw refused;
            // The console's wording speaks of deleting tickets, which a holder cannot do.
            String reason = refused.getReason();
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                reason != null && reason.contains("이미 있어요")
                    ? "이 주소로는 이미 이 이벤트의 입장권이 있어요." : reason);
        }
        long id = ((Number) issued.get("ticketId")).longValue();
        return inviteRow(ticketRows.findById(id).orElseThrow());
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
        Ticket ticket = holder(resolved, session);
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
        tickets.delete(invited.id, false);
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
