package com.plink.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
            @Value("${plink.auth.google-client-id}") String clientId,
            @Value("${plink.auth.google-client-secret}") String clientSecret,
            @Value("${plink.auth.base-url}") String baseUrl) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/links/s/**").permitAll()
                // Holder routes are guarded by the personal token plus the bound passkey.
                .requestMatchers("/api/t/**").permitAll()
                // Gate terminals authenticate with their own token, checked in the controller.
                .requestMatchers("/api/gates/**").permitAll()
                .requestMatchers("/api/admin/**").authenticated()
                .requestMatchers("/api/links", "/api/links/**").authenticated()
                .requestMatchers("/h2-console/**").denyAll()
                .anyRequest().permitAll())
            .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, error) -> response.sendError(401)))
            .cors(Customizer.withDefaults())
            .requestCache(RequestCacheConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(logout -> logout.logoutUrl("/api/auth/logout")
                .invalidateHttpSession(true).clearAuthentication(true)
                .deleteCookies("JSESSIONID")
                .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)));
        // CSRF remains enabled, including logout and link mutations.
        // The H2 console is denied above, so it needs no CSRF or frame-options exemption.
        // Gate terminals carry no session cookie: their authority is the X-Gate-Token
        // header, which a cross-site page cannot set, so CSRF adds nothing there.
        http.csrf(csrf -> csrf.ignoringRequestMatchers("/api/gates/**"));
        if (!clientId.trim().isEmpty() && !clientSecret.trim().isEmpty()) {
            ClientRegistration google = CommonOAuth2Provider.GOOGLE.getBuilder("google")
                .clientId(clientId).clientSecret(clientSecret)
                .redirectUri(baseUrl + "/login/oauth2/code/google").build();
            http.oauth2Login(oauth -> oauth
                .clientRegistrationRepository(new InMemoryClientRegistrationRepository(google))
                .loginPage(baseUrl + "/login")
                .defaultSuccessUrl(baseUrl + "/manage", true)
                .failureUrl(baseUrl + "/login?error=google"));
        }
        return http.build();
    }
}
