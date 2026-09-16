package com.plink.account;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.util.Optional;

/**
 * One view of "who is signed in" across both ways of signing in. Controllers ask this
 * rather than a specific principal type, so adding local accounts did not turn every
 * ownership check into a pair of branches.
 */
public final class CurrentUser {
    public final String subject, email, name;

    private CurrentUser(String subject, String email, String name) {
        this.subject = subject;
        this.email = email;
        this.name = name;
    }

    public static Optional<CurrentUser> of(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) return Optional.empty();
        Object principal = authentication.getPrincipal();
        if (principal instanceof OidcUser user) {
            // Google subjects stay the owner key they have always been.
            return Optional.of(new CurrentUser(user.getSubject(), user.getEmail(), user.getFullName()));
        }
        if (principal instanceof AdminPrincipal admin) {
            return Optional.of(new CurrentUser(admin.subject(), admin.email, admin.displayName));
        }
        return Optional.empty();
    }
}
