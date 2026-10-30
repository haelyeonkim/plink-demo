package com.plink.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A Google account is let in by a decision, never by being a Google account. */
class GoogleAccessTest {

    @Test void nothingConfiguredAdmitsNobody() {
        GoogleAccess access = new GoogleAccess("", null);
        assertFalse(access.configured());
        assertFalse(access.allows("anyone@gmail.com", true, null));
        assertFalse(access.allows("staff@gallery.example", true, "gallery.example"));
    }

    @Test void aWorkspaceDomainIsReadFromTheHostedDomainClaim() {
        GoogleAccess access = new GoogleAccess("Gallery.example, other.example", "");
        assertTrue(access.allows("Staff@gallery.example", true, "gallery.example"));
        // The address alone is not the domain: a consumer account carries no hd claim.
        assertFalse(access.allows("staff@gallery.example", true, null));
        // And the claim has to be about this address.
        assertFalse(access.allows("someone@gmail.com", true, "gallery.example"));
    }

    @Test void listedAddressesGetInWhateverTheirDomain() {
        GoogleAccess access = new GoogleAccess(null, "owner@gmail.com");
        assertTrue(access.allows("OWNER@gmail.com", true, null));
        assertFalse(access.allows("other@gmail.com", true, null));
    }

    @Test void anUnverifiedAddressIsNotTrusted() {
        GoogleAccess access = new GoogleAccess("gallery.example", "owner@gmail.com");
        assertFalse(access.allows("owner@gmail.com", false, null));
        assertFalse(access.allows("staff@gallery.example", false, "gallery.example"));
    }
}
