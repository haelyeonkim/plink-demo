package com.plink.account;

import java.security.Principal;

/** The authenticated local administrator. Carries no password material. */
public class AdminPrincipal implements Principal {
    /** Authority names the filter chain matches on. */
    public static final String LINKS = "SCOPE_LINKS";
    public static final String TICKETS = "SCOPE_TICKETS";
    public static final String ACCOUNTS = "SCOPE_ACCOUNTS";

    public final long id;
    public final String email, displayName;
    public final boolean canLinks, canTickets, owner;
    /**
     * Fingerprint of the password hash at sign-in. A reset or change produces a new
     * hash, and a session holding the old fingerprint is then no longer honoured.
     */
    public final String credentialStamp;

    public AdminPrincipal(AdminAccount account) {
        this.id = account.id;
        this.email = account.email;
        this.displayName = account.displayName;
        this.canLinks = account.canLinks;
        this.canTickets = account.canTickets;
        this.owner = account.owner;
        this.credentialStamp = stamp(account.passwordHash);
    }

    /** A digest of the BCrypt hash, so the principal itself carries no hash to crack. */
    public static String stamp(String passwordHash) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(String.valueOf(passwordHash).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 16);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Whether this principal still says what the row says. */
    public boolean matches(AdminAccount account) {
        return account.id == id && account.canLinks == canLinks && account.canTickets == canTickets
            && account.owner == owner && java.util.Objects.equals(account.email, email)
            && java.util.Objects.equals(account.displayName, displayName);
    }

    /** ROLE_ADMIN says who they are; the scopes say which console they may open. */
    public java.util.List<String> authorities() {
        java.util.List<String> authorities = new java.util.ArrayList<>();
        authorities.add("ROLE_ADMIN");
        if (canLinks) authorities.add(LINKS);
        if (canTickets) authorities.add(TICKETS);
        if (owner) authorities.add(ACCOUNTS);
        return authorities;
    }

    public String subject() { return "local:" + id; }

    @Override public String getName() { return email; }
}
