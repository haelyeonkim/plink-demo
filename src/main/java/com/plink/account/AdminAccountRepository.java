package com.plink.account;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
public class AdminAccountRepository {
    private final JdbcTemplate jdbc;
    public AdminAccountRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<AdminAccount> MAPPER = (rs, row) -> {
        AdminAccount account = new AdminAccount();
        account.id = rs.getLong("id");
        account.email = rs.getString("email");
        account.passwordHash = rs.getString("password_hash");
        account.displayName = rs.getString("display_name");
        account.failedLogins = rs.getInt("failed_logins");
        account.lockedUntil = rs.getTimestamp("locked_until");
        account.lastLoginAt = rs.getTimestamp("last_login_at");
        account.createdAt = rs.getTimestamp("created_at");
        account.canLinks = rs.getBoolean("can_links");
        account.canTickets = rs.getBoolean("can_tickets");
        account.owner = rs.getBoolean("is_owner");
        account.slug = rs.getString("slug");
        return account;
    };

    public Optional<AdminAccount> findByEmail(String email) {
        return jdbc.query("SELECT * FROM admin_account WHERE email = ?", MAPPER, email).stream().findFirst();
    }

    public Optional<AdminAccount> findById(long id) {
        return jdbc.query("SELECT * FROM admin_account WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public List<AdminAccount> findAll() {
        return jdbc.query("SELECT * FROM admin_account ORDER BY id", MAPPER);
    }

    public long insert(String email, String passwordHash, String displayName) {
        return insert(email, passwordHash, displayName, true, true, false);
    }

    public long insert(String email, String passwordHash, String displayName,
            boolean canLinks, boolean canTickets, boolean owner) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO admin_account (email, password_hash, display_name, can_links, can_tickets, "
                + "is_owner, slug) VALUES (?, ?, ?, ?, ?, ?, ?)",
                new String[] { "id" });
            ps.setString(1, email);
            ps.setString(2, passwordHash);
            ps.setString(3, displayName);
            ps.setBoolean(4, canLinks);
            ps.setBoolean(5, canTickets);
            ps.setBoolean(6, owner);
            ps.setString(7, freeSlug(email));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * A path segment for a new account, taken from its address and made unique. The
     * owner can rename it afterwards; this only has to be usable and free.
     */
    private String freeSlug(String email) {
        String local = email.substring(0, Math.max(email.indexOf('@'), 0))
            .toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
        String base = local.replace("-", "").isEmpty() ? "u" : local;
        if (base.length() > 30) base = base.substring(0, 30);
        String candidate = base;
        for (int suffix = 2; findBySlug(candidate).isPresent(); suffix++) {
            candidate = base + "-" + suffix;
        }
        return candidate;
    }

    public Optional<AdminAccount> findBySlug(String slug) {
        return jdbc.query("SELECT * FROM admin_account WHERE slug = ?", MAPPER, slug).stream().findFirst();
    }

    public void updateSlug(long id, String slug) {
        jdbc.update("UPDATE admin_account SET slug = ? WHERE id = ?", slug, id);
    }

    public void updatePermissions(long id, boolean canLinks, boolean canTickets, boolean owner) {
        jdbc.update("UPDATE admin_account SET can_links = ?, can_tickets = ?, is_owner = ? WHERE id = ?",
            canLinks, canTickets, owner, id);
    }

    public int delete(long id) {
        return jdbc.update("DELETE FROM admin_account WHERE id = ?", id);
    }

    /** Used to refuse the change that would leave nobody able to manage accounts. */
    public int countOwners() {
        Integer value = jdbc.queryForObject(
            "SELECT COUNT(*) FROM admin_account WHERE is_owner = TRUE", Integer.class);
        return value == null ? 0 : value;
    }

    public void updatePassword(long id, String passwordHash) {
        jdbc.update("UPDATE admin_account SET password_hash = ?, failed_logins = 0, locked_until = NULL "
            + "WHERE id = ?", passwordHash, id);
    }

    public void recordSuccess(long id) {
        jdbc.update("UPDATE admin_account SET failed_logins = 0, locked_until = NULL, "
            + "last_login_at = CURRENT_TIMESTAMP WHERE id = ?", id);
    }

    public void recordFailure(long id, Timestamp lockedUntil) {
        jdbc.update("UPDATE admin_account SET failed_logins = failed_logins + 1, locked_until = ? WHERE id = ?",
            lockedUntil, id);
    }

    public int count() {
        Integer value = jdbc.queryForObject("SELECT COUNT(*) FROM admin_account", Integer.class);
        return value == null ? 0 : value;
    }
}
