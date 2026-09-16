package com.plink.ticket.config;

import com.plink.ticket.repository.HolderRepository;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.data.AttestationConveyancePreference;
import com.yubico.webauthn.data.PublicKeyCredentialParameters;
import com.yubico.webauthn.data.RelyingPartyIdentity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;

@Configuration
public class TicketRelyingPartyConfig {

    /**
     * The relying party for every passkey on this domain - tickets and private links
     * alike, since both bind to the same person. Attestation is requested directly so a
     * registration can read the authenticator's AAGUID and refuse desktop authenticators
     * when a session is mobile-only.
     */
    @Bean
    RelyingParty ticketRelyingParty(HolderRepository repository,
            @Value("${plink.auth.base-url}") String baseUrl) {
        URI origin = URI.create(baseUrl);
        if (origin.getHost() == null
                || !("https".equals(origin.getScheme())
                     || ("http".equals(origin.getScheme()) && "localhost".equals(origin.getHost())))) {
            throw new IllegalArgumentException("APP_BASE_URL must be an HTTPS origin or http://localhost");
        }
        return RelyingParty.builder()
            .identity(RelyingPartyIdentity.builder().id(origin.getHost()).name("Passlink").build())
            .credentialRepository(repository)
            .origins(Collections.singleton(baseUrl))
            .preferredPubkeyParams(Arrays.asList(PublicKeyCredentialParameters.ES256, PublicKeyCredentialParameters.RS256))
            .attestationConveyancePreference(AttestationConveyancePreference.DIRECT)
            .allowUntrustedAttestation(true)
            .build();
    }
}
