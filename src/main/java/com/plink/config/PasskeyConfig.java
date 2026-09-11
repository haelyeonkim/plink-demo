package com.plink.config;

import com.plink.repository.PasskeyRepository;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.data.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import java.net.URI;
import java.util.*;

@Configuration
public class PasskeyConfig {
    @Bean
    @Primary
    RelyingParty relyingParty(PasskeyRepository repository, @Value("${plink.auth.base-url}") String baseUrl) {
        URI origin = URI.create(baseUrl);
        if (origin.getHost() == null || origin.getUserInfo() != null || origin.getQuery() != null
                || origin.getFragment() != null || (origin.getPath() != null && !origin.getPath().isEmpty())
                || !("https".equals(origin.getScheme()) || ("http".equals(origin.getScheme()) && "localhost".equals(origin.getHost())))) {
            throw new IllegalArgumentException("APP_BASE_URL must be an HTTPS origin or http://localhost with no trailing slash");
        }
        return RelyingParty.builder()
            .identity(RelyingPartyIdentity.builder().id(origin.getHost()).name("P-Link").build())
            .credentialRepository(repository).origins(Collections.singleton(baseUrl))
            .preferredPubkeyParams(Arrays.asList(PublicKeyCredentialParameters.ES256, PublicKeyCredentialParameters.RS256))
            .build();
    }
}
