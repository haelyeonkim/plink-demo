package com.plink.config;

import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Who may sign in with Google.
 *
 * <p>Google will vouch for anybody with a Google account, and a Google sign-in opens
 * both consoles, so being a Google user cannot be the test. A Workspace domain (the
 * verified "hd" claim) or a listed address is. With neither configured nobody gets in:
 * an empty list is a deployment that has not decided yet, not one that admits everyone.
 */
public final class GoogleAccess {
    private final Set<String> domains;
    private final Set<String> emails;

    public GoogleAccess(String domains, String emails) {
        this.domains = split(domains);
        this.emails = split(emails);
    }

    private static Set<String> split(String value) {
        if (value == null) return Set.of();
        return Arrays.stream(value.split("[,\\s]+"))
            .map(entry -> entry.trim().toLowerCase(Locale.ROOT))
            .filter(entry -> !entry.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
    }

    public boolean configured() {
        return !domains.isEmpty() || !emails.isEmpty();
    }

    public boolean allows(OidcUser user) {
        return allows(user.getEmail(), Boolean.TRUE.equals(user.getEmailVerified()),
            user.getClaimAsString("hd"));
    }

    /**
     * @param hostedDomain the "hd" claim, which Google sets only for Workspace accounts
     *     and which, unlike the part after the @, a consumer account cannot claim
     */
    public boolean allows(String email, boolean emailVerified, String hostedDomain) {
        if (email == null || !emailVerified) return false;
        String address = email.trim().toLowerCase(Locale.ROOT);
        if (emails.contains(address)) return true;
        if (hostedDomain == null) return false;
        String domain = hostedDomain.trim().toLowerCase(Locale.ROOT);
        return domains.contains(domain) && address.endsWith("@" + domain);
    }
}
