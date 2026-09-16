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

    public AdminPrincipal(AdminAccount account) {
        this.id = account.id;
        this.email = account.email;
        this.displayName = account.displayName;
        this.canLinks = account.canLinks;
        this.canTickets = account.canTickets;
        this.owner = account.owner;
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
