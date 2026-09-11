package com.plink.ticket.repository;

import com.yubico.webauthn.CredentialRepository;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * One passkey per ticket URL. The WebAuthn username is the ticket reference, so the
 * server always checks the asserted credential against the binding for that ticket
 * rather than trusting whichever passkey the browser offered for the domain.
 */
@Repository
public class TicketPasskeyRepository implements CredentialRepository {
    private final JdbcTemplate jdbc;
    public TicketPasskeyRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public static class Binding {
        public long ticketId;
        public String ticketRef, credentialId, userHandle, publicKey, holderEmail, aaguid;
        public long signatureCount;

        public RegisteredCredential credential() {
            return RegisteredCredential.builder()
                .credentialId(decode(credentialId))
                .userHandle(decode(userHandle))
                .publicKeyCose(decode(publicKey))
                .signatureCount(signatureCount)
                .build();
        }
    }

    public static ByteArray decode(String value) {
        try { return ByteArray.fromBase64Url(value); }
        catch (Exception e) { throw new IllegalStateException("Invalid stored credential", e); }
    }

    private List<Binding> query(String condition, Object... args) {
        return jdbc.query("SELECT p.*, t.ticket_ref FROM ticket_passkey p "
            + "JOIN ticket t ON t.id = p.ticket_id WHERE " + condition,
            (rs, row) -> {
                Binding b = new Binding();
                b.ticketId = rs.getLong("ticket_id");
                b.ticketRef = rs.getString("ticket_ref");
                b.credentialId = rs.getString("credential_id");
                b.userHandle = rs.getString("user_handle");
                b.publicKey = rs.getString("public_key");
                b.signatureCount = rs.getLong("signature_count");
                b.holderEmail = rs.getString("holder_email");
                b.aaguid = rs.getString("aaguid");
                return b;
            }, args);
    }

    public Optional<Binding> findByTicketId(long ticketId) {
        return query("p.ticket_id = ?", ticketId).stream().findFirst();
    }

    public Optional<Binding> findByRef(String ticketRef) {
        return query("t.ticket_ref = ?", ticketRef).stream().findFirst();
    }

    public void insert(long ticketId, ByteArray credentialId, ByteArray userHandle, ByteArray publicKey,
            long signatureCount, String holderEmail, String aaguid) {
        jdbc.update("INSERT INTO ticket_passkey (ticket_id, credential_id, user_handle, public_key, "
            + "signature_count, holder_email, aaguid) VALUES (?, ?, ?, ?, ?, ?, ?)",
            ticketId, credentialId.getBase64Url(), userHandle.getBase64Url(), publicKey.getBase64Url(),
            signatureCount, holderEmail, aaguid);
    }

    public void updateCount(long ticketId, long count) {
        jdbc.update("UPDATE ticket_passkey SET signature_count = ? WHERE ticket_id = ?", count, ticketId);
    }

    /** Recovery and transfer both drop the binding so the rotated URL can be claimed afresh. */
    public void deleteByTicketId(long ticketId) {
        jdbc.update("DELETE FROM ticket_passkey WHERE ticket_id = ?", ticketId);
    }

    @Override public Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(String username) {
        Set<PublicKeyCredentialDescriptor> ids = new HashSet<>();
        findByRef(username).ifPresent(b -> ids.add(
            PublicKeyCredentialDescriptor.builder().id(decode(b.credentialId)).build()));
        return ids;
    }

    @Override public Optional<ByteArray> getUserHandleForUsername(String username) {
        return findByRef(username).map(b -> decode(b.userHandle));
    }

    @Override public Optional<String> getUsernameForUserHandle(ByteArray handle) {
        return query("p.user_handle = ?", handle.getBase64Url()).stream().map(b -> b.ticketRef).findFirst();
    }

    @Override public Optional<RegisteredCredential> lookup(ByteArray id, ByteArray handle) {
        return query("p.credential_id = ? AND p.user_handle = ?", id.getBase64Url(), handle.getBase64Url())
            .stream().map(Binding::credential).findFirst();
    }

    @Override public Set<RegisteredCredential> lookupAll(ByteArray id) {
        Set<RegisteredCredential> result = new HashSet<>();
        query("p.credential_id = ?", id.getBase64Url()).forEach(b -> result.add(b.credential()));
        return result;
    }
}
