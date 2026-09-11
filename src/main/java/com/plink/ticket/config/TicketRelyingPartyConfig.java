package com.plink.ticket.config;

import com.plink.ticket.repository.TicketPasskeyRepository;
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
     * A second relying party for tickets. Attestation is requested directly so the
     * registration can read the authenticator's AAGUID and refuse desktop
     * authenticators when the session is mobile-only.
     */
    @Bean
    RelyingParty ticketRelyingParty(TicketPasskeyRepository repository,
            @Value("${plink.auth.base-url}") String baseUrl) {
        URI origin = URI.create(baseUrl);
        if (origin.getHost() == null
                || !("https".equals(origin.getScheme())
                     || ("http".equals(origin.getScheme()) && "localhost".equals(origin.getHost())))) {
            throw new IllegalArgumentException("APP_BASE_URL must be an HTTPS origin or http://localhost");
        }
        return RelyingParty.builder()
            .identity(RelyingPartyIdentity.builder().id(origin.getHost()).name("P-Link Gate").build())
            .credentialRepository(repository)
            .origins(Collections.singleton(baseUrl))
            .preferredPubkeyParams(Arrays.asList(PublicKeyCredentialParameters.ES256, PublicKeyCredentialParameters.RS256))
            .attestationConveyancePreference(AttestationConveyancePreference.DIRECT)
            .allowUntrustedAttestation(true)
            .build();
    }
}
