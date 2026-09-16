package com.plink.ticket.repository;

import com.yubico.webauthn.CredentialRepository;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Passkeys belong to a person, addressed by their verified email.
 *
 * <p>The WebAuthn username here is that email, so the browser offers one identity for
 * the domain no matter how many tickets it covers, and a second device adds a
 * credential to the same identity rather than starting a new one.
 */
@Repository
public class HolderRepository implements CredentialRepository {
    private final JdbcTemplate jdbc;
    public HolderRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public static class Holder {
        public long id;
        public String email, userHandle;
    }

    public static class Credential {
        public long holderId;
        public String credentialId, publicKey, aaguid, email, userHandle;
        public long signatureCount;

        public RegisteredCredential registered() {
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

    private static final RowMapper<Holder> HOLDER = (rs, row) -> {
        Holder h = new Holder();
        h.id = rs.getLong("id");
        h.email = rs.getString("email");
        h.userHandle = rs.getString("user_handle");
        return h;
    };

    private static final RowMapper<Credential> CREDENTIAL = (rs, row) -> {
        Credential c = new Credential();
        c.holderId = rs.getLong("holder_id");
        c.credentialId = rs.getString("credential_id");
        c.publicKey = rs.getString("public_key");
        c.signatureCount = rs.getLong("signature_count");
        c.aaguid = rs.getString("aaguid");
        c.email = rs.getString("email");
        c.userHandle = rs.getString("user_handle");
        return c;
    };

    public Optional<Holder> findByEmail(String email) {
        return jdbc.query("SELECT * FROM holder WHERE email = ?", HOLDER, email).stream().findFirst();
    }

    public Optional<Holder> findById(long id) {
        return jdbc.query("SELECT * FROM holder WHERE id = ?", HOLDER, id).stream().findFirst();
    }

    public long create(String email, String userHandle) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO holder (email, user_handle) VALUES (?, ?)", new String[] { "id" });
            ps.setString(1, email);
            ps.setString(2, userHandle);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    private List<Credential> query(String condition, Object... args) {
        return jdbc.query("SELECT c.*, h.email, h.user_handle FROM holder_credential c "
            + "JOIN holder h ON h.id = c.holder_id WHERE " + condition, CREDENTIAL, args);
    }

    public List<Credential> findByHolder(long holderId) { return query("c.holder_id = ?", holderId); }

    public List<Credential> findByEmailAddress(String email) { return query("h.email = ?", email); }

    public Optional<Credential> findByCredentialId(String credentialId) {
        return query("c.credential_id = ?", credentialId).stream().findFirst();
    }

    /** Registering the same handle on a device that already holds one replaces it there. */
    public void insert(long holderId, ByteArray credentialId, ByteArray publicKey,
            long signatureCount, String aaguid) {
        jdbc.update("DELETE FROM holder_credential WHERE credential_id = ?", credentialId.getBase64Url());
        jdbc.update("INSERT INTO holder_credential (credential_id, holder_id, public_key, signature_count, "
            + "aaguid) VALUES (?, ?, ?, ?, ?)",
            credentialId.getBase64Url(), holderId, publicKey.getBase64Url(), signatureCount, aaguid);
    }

    public void recordUse(String credentialId, long signatureCount) {
        jdbc.update("UPDATE holder_credential SET signature_count = ?, last_used_at = CURRENT_TIMESTAMP "
            + "WHERE credential_id = ?", signatureCount, credentialId);
    }

    @Override public Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(String username) {
        Set<PublicKeyCredentialDescriptor> ids = new HashSet<>();
        for (Credential c : findByEmailAddress(username)) {
            ids.add(PublicKeyCredentialDescriptor.builder().id(decode(c.credentialId)).build());
        }
        return ids;
    }

    @Override public Optional<ByteArray> getUserHandleForUsername(String username) {
        return findByEmail(username).map(h -> decode(h.userHandle));
    }

    @Override public Optional<String> getUsernameForUserHandle(ByteArray handle) {
        return jdbc.query("SELECT * FROM holder WHERE user_handle = ?", HOLDER, handle.getBase64Url())
            .stream().map(h -> h.email).findFirst();
    }

    @Override public Optional<RegisteredCredential> lookup(ByteArray id, ByteArray handle) {
        return query("c.credential_id = ? AND h.user_handle = ?", id.getBase64Url(), handle.getBase64Url())
            .stream().map(Credential::registered).findFirst();
    }

    @Override public Set<RegisteredCredential> lookupAll(ByteArray id) {
        Set<RegisteredCredential> result = new HashSet<>();
        query("c.credential_id = ?", id.getBase64Url()).forEach(c -> result.add(c.registered()));
        return result;
    }
}
