package com.plink.repository;

import com.yubico.webauthn.CredentialRepository;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public class PasskeyRepository implements CredentialRepository {
    private final JdbcTemplate jdbc;
    public PasskeyRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public static class Binding {
        public long linkId;
        public String code, credentialId, userHandle, publicKey, receiverName;
        public long signatureCount;
        public RegisteredCredential credential() {
            return RegisteredCredential.builder().credentialId(decode(credentialId))
                .userHandle(decode(userHandle)).publicKeyCose(decode(publicKey))
                .signatureCount(signatureCount).build();
        }
    }
    public static ByteArray decode(String value) {
        try { return ByteArray.fromBase64Url(value); }
        catch (Exception e) { throw new IllegalStateException("Invalid stored credential", e); }
    }
    private List<Binding> query(String condition, Object... args) {
        return jdbc.query("SELECT p.*, l.short_code FROM link_passkey p JOIN protected_link l ON l.id=p.link_id WHERE " + condition,
            (rs, row) -> {
                Binding b = new Binding();
                b.linkId = rs.getLong("link_id"); b.code = rs.getString("short_code");
                b.credentialId = rs.getString("credential_id"); b.userHandle = rs.getString("user_handle");
                b.publicKey = rs.getString("public_key"); b.signatureCount = rs.getLong("signature_count");
                b.receiverName = rs.getString("receiver_name"); return b;
            }, args);
    }
    public Optional<Binding> findByLinkId(long id) { return query("p.link_id=?", id).stream().findFirst(); }
    public Optional<Binding> findByCode(String code) { return query("l.short_code=?", code).stream().findFirst(); }
    public void insert(long linkId, ByteArray credentialId, ByteArray handle, ByteArray key, long count, String name) {
        jdbc.update("INSERT INTO link_passkey (link_id,credential_id,user_handle,public_key,signature_count,receiver_name) VALUES (?,?,?,?,?,?)",
            linkId, credentialId.getBase64Url(), handle.getBase64Url(), key.getBase64Url(), count, name);
    }
    public void updateCount(long linkId, long count) {
        jdbc.update("UPDATE link_passkey SET signature_count=? WHERE link_id=?", count, linkId);
    }
    @Override public Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(String username) {
        Set<PublicKeyCredentialDescriptor> ids = new HashSet<>();
        findByCode(username).ifPresent(b -> ids.add(PublicKeyCredentialDescriptor.builder().id(decode(b.credentialId)).build()));
        return ids;
    }
    @Override public Optional<ByteArray> getUserHandleForUsername(String username) {
        return findByCode(username).map(b -> decode(b.userHandle));
    }
    @Override public Optional<String> getUsernameForUserHandle(ByteArray handle) {
        return query("p.user_handle=?", handle.getBase64Url()).stream().map(b -> b.code).findFirst();
    }
    @Override public Optional<RegisteredCredential> lookup(ByteArray id, ByteArray handle) {
        return query("p.credential_id=? AND p.user_handle=?", id.getBase64Url(), handle.getBase64Url())
            .stream().map(Binding::credential).findFirst();
    }
    @Override public Set<RegisteredCredential> lookupAll(ByteArray id) {
        Set<RegisteredCredential> result = new HashSet<>();
        query("p.credential_id=?", id.getBase64Url()).forEach(b -> result.add(b.credential()));
        return result;
    }
}
