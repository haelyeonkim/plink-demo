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
 * Passkeys belong to a person, and the person is whoever holds them.
 *
 * <p>The WebAuthn username here is the holder's user handle: a holder may have no email
 * at all, and the handle is the one thing every holder has. Holders made when an email
 * named the person keep the handle derived from it, so their passkeys still answer.
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

    public Optional<Holder> findByHandle(String userHandle) {
        return jdbc.query("SELECT * FROM holder WHERE user_handle = ?", HOLDER, userHandle).stream().findFirst();
    }

    /**
     * Holders that have already claimed something sent to this email or phone, and the
     * holder an email named before claims went by passkey. Only a hint for which
     * ceremony a claim page offers first: nothing is bound on it, the passkey decides.
     */
    public List<Long> claimedByContact(String email, String phone) {
        String mail = email == null || email.isBlank() ? null : email.trim().toLowerCase(java.util.Locale.ROOT);
        List<String> numbers = phoneForms(phone);
        if (mail == null && numbers.isEmpty()) return List.of();
        // Unmatchable placeholders keep the statement one shape whether or not a phone was given.
        String a = numbers.size() > 0 ? numbers.get(0) : "-", b = numbers.size() > 1 ? numbers.get(1) : "-",
            c = numbers.size() > 2 ? numbers.get(2) : "-";
        return jdbc.queryForList("SELECT DISTINCT holder_id FROM ("
            + "SELECT holder_id FROM link_recipient WHERE holder_id IS NOT NULL AND LOWER(email) = ? "
            + "UNION SELECT holder_id FROM ticket WHERE holder_id IS NOT NULL "
            + "AND (LOWER(issued_to_email) = ? OR phone IN (?, ?, ?)) "
            + "UNION SELECT id AS holder_id FROM holder WHERE LOWER(email) = ?) claimed "
            // A person is somebody with a passkey: a registration given up halfway, under the
            // old flow, left a holder with an address and nothing to answer with.
            + "WHERE EXISTS (SELECT 1 FROM holder_credential c WHERE c.holder_id = claimed.holder_id)",
            Long.class, mail, mail, a, b, c, mail);
    }

    /**
     * The ways one Korean mobile number is stored: as dialled at home (010...) and with the
     * country code, with or without the plus. Issuing keeps whatever form it was given.
     */
    static List<String> phoneForms(String phone) {
        if (phone == null || phone.isBlank()) return List.of();
        String digits = phone.replaceAll("\\D", "");
        String local = digits.startsWith("82") && digits.length() >= 11 ? "0" + digits.substring(2) : digits;
        if (local.length() < 9) return List.of();
        if (!local.startsWith("0")) return List.of(local);
        String national = local.substring(1);
        return List.of(local, "+82" + national, "82" + national);
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

    /** The holders' passkeys, as a ceremony lists them to allow or to exclude. */
    public Set<PublicKeyCredentialDescriptor> descriptors(List<Long> holderIds) {
        Set<PublicKeyCredentialDescriptor> ids = new HashSet<>();
        for (long holderId : holderIds) {
            for (Credential c : findByHolder(holderId)) {
                ids.add(PublicKeyCredentialDescriptor.builder().id(decode(c.credentialId)).build());
            }
        }
        return ids;
    }

    @Override public Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(String username) {
        return findByHandle(username).map(h -> descriptors(List.of(h.id))).orElseGet(HashSet::new);
    }

    @Override public Optional<ByteArray> getUserHandleForUsername(String username) {
        return findByHandle(username).map(h -> decode(h.userHandle));
    }

    @Override public Optional<String> getUsernameForUserHandle(ByteArray handle) {
        return findByHandle(handle.getBase64Url()).map(h -> h.userHandle);
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
