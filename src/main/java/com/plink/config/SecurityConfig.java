package com.plink.config;

import com.plink.account.AdminPrincipal;
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
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

@Configuration
public class SecurityConfig {

    /**
     * Grants a console to an account that carries its scope.
     *
     * <p>Google sign-in predates per-account permissions and has no row to read them
     * from, so a tenancy user keeps the two consoles they have always had - but not
     * account administration, which nobody had before.
     */
    private static AuthorizationManager<RequestAuthorizationContext> scope(String authority,
            boolean allowTenancyUsers) {
        return (authentication, context) -> {
            Authentication current = authentication.get();
            if (current == null) return new AuthorizationDecision(false);
            if (allowTenancyUsers && current.getPrincipal() instanceof OidcUser) {
                return new AuthorizationDecision(true);
            }
            boolean granted = current.getPrincipal() instanceof AdminPrincipal
                && current.getAuthorities().stream()
                    .anyMatch(held -> authority.equals(held.getAuthority()));
            return new AuthorizationDecision(granted);
        };
    }

    /** Administrator passwords are stored only as BCrypt hashes. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
            @Value("${plink.auth.google-client-id}") String clientId,
            @Value("${plink.auth.google-client-secret}") String clientSecret,
            @Value("${plink.auth.base-url}") String baseUrl) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/links/s/**").permitAll()
                // Holder routes are guarded by the personal token plus the bound passkey.
                .requestMatchers("/api/tickets/**").permitAll()
                // Gate terminals enrol with a setup link and code, then authenticate with their
                // own token; both are checked in the controller.
                .requestMatchers("/api/gates/**").permitAll()
                // Per-account permissions. An account without the scope is refused here
                // whatever the menu decides to show it.
                .requestMatchers("/api/accounts", "/api/accounts/**")
                    .access(scope(AdminPrincipal.ACCOUNTS, false))
                .requestMatchers("/api/admin/**").access(scope(AdminPrincipal.TICKETS, true))
                .requestMatchers("/api/links", "/api/links/**").access(scope(AdminPrincipal.LINKS, true))
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
                .defaultSuccessUrl(baseUrl + "/", true)
                .failureUrl(baseUrl + "/login?error=google"));
        }
        return http.build();
    }
}
