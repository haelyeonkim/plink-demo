package com.plink.service;

import com.plink.account.AdminAccountRepository;
import com.plink.model.LinkRecipient;
import com.plink.model.ProtectedLink;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Where a recipient's address is published.
 *
 * <p>Addresses are namespaced by the account that issued them - {@code /s/{slug}/{code}}
 * - so the person receiving one can see who sent it, and an account can later be moved
 * onto its own domain without touching the codes. The code alone still resolves, which
 * is what keeps every address handed out before this change working.
 */
@Component
public class LinkAddresses {
    private static final String LOCAL_PREFIX = "local:";

    private final AdminAccountRepository accounts;
    private final String baseUrl;

    public LinkAddresses(AdminAccountRepository accounts, @Value("${plink.auth.base-url}") String baseUrl) {
        this.accounts = accounts;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** The issuing account's segment, absent for links owned by a Google tenancy user. */
    public Optional<String> slugFor(ProtectedLink link) {
        String owner = link.getOwnerSub();
        if (owner == null || !owner.startsWith(LOCAL_PREFIX)) return Optional.empty();
        try {
            long id = Long.parseLong(owner.substring(LOCAL_PREFIX.length()));
            return accounts.findById(id).map(account -> account.slug);
        } catch (NumberFormatException notLocal) {
            return Optional.empty();
        }
    }

    public String path(ProtectedLink link, LinkRecipient recipient) {
        return slugFor(link).map(slug -> "/s/" + slug + "/" + recipient.shortCode)
            .orElse("/s/" + recipient.shortCode);
    }

    public String url(ProtectedLink link, LinkRecipient recipient) {
        return baseUrl + path(link, recipient);
    }

    /** True when a slug in the address names the account that actually issued it. */
    public boolean matches(ProtectedLink link, String slug) {
        if (slug == null || slug.isBlank()) return true;
        return slugFor(link).map(slug::equals).orElse(false);
    }
}
