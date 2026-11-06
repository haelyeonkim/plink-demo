package com.plink.config;

import com.plink.activity.Activity;
import com.plink.activity.ActivityLog;
import com.plink.account.AdminAccountRepository;
import com.plink.account.AdminPrincipal;
import com.plink.account.AdminSessionCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.context.SecurityContextHolderFilter;

@Configuration
public class SecurityConfig {
    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /** The error code a refused Google account carries to the failure handler. */
    static final String GOOGLE_DENIED = "access_denied";

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
            @Value("${plink.auth.base-url}") String baseUrl,
            @Value("${plink.auth.google-allowed-domains:}") String allowedDomains,
            @Value("${plink.auth.google-allowed-emails:}") String allowedEmails,
            AdminAccountRepository accounts) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                // Live channels authorise themselves at the handshake: the holder's by
                // the token in the path, the console's by the signed-in session.
                .requestMatchers("/ws/**").permitAll()
                .requestMatchers("/api/links/s/**").permitAll()
                // Holder routes are guarded by the personal token plus the bound passkey.
                .requestMatchers("/api/tickets/**").permitAll()
                // Booth managers likewise: the invite token plus the mailbox, then the bound
                // passkey. Session cookies carry the sign-in, so CSRF stays on for these.
                .requestMatchers("/api/booth/**").permitAll()
                // Gate terminals enrol with a setup link and code, then authenticate with their
                // own token; both are checked in the controller.
                .requestMatchers("/api/gates/**").permitAll()
                // Per-account permissions. An account without the scope is refused here
                // whatever the menu decides to show it.
                .requestMatchers("/api/accounts", "/api/accounts/**")
                    .access(scope(AdminPrincipal.ACCOUNTS, false))
                .requestMatchers("/api/admin/artworks", "/api/admin/artworks/**")
                    .access(scope(AdminPrincipal.ACCOUNTS, false))
                // The service's own records: every event's people, so owners only.
                .requestMatchers("/api/admin/data", "/api/admin/data/**")
                    .access(scope(AdminPrincipal.ACCOUNTS, false))
                .requestMatchers("/api/admin/**").access(scope(AdminPrincipal.TICKETS, true))
                .requestMatchers("/api/links", "/api/links/**").access(scope(AdminPrincipal.LINKS, true))
                // Content is written by whoever may issue links; it exists only for them.
                .requestMatchers("/api/contents", "/api/contents/**").access(scope(AdminPrincipal.LINKS, true))
                .requestMatchers("/h2-console/**").denyAll()
                .anyRequest().permitAll())
            .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, error) -> {
                response.setStatus(401);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"로그인이 만료됐어요. 다시 로그인해 주세요.\"}");
            }))
            .cors(Customizer.withDefaults())
            .requestCache(RequestCacheConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(logout -> logout.logoutUrl("/api/auth/logout")
                .invalidateHttpSession(true).clearAuthentication(true)
                .deleteCookies("JSESSIONID")
                .addLogoutHandler((request, response, authentication) -> {
                    // Before the session goes: afterwards nobody is left to say who it was.
                    Activity row = Activity.of(Activity.AUTH, "logout").always();
                    if (authentication != null && authentication.getPrincipal() instanceof AdminPrincipal admin) {
                        row.actor("ADMIN", String.valueOf(admin.id), admin.email).summary(admin.email);
                    }
                    ActivityLog.emit(row);
                })
                .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)));
        // CSRF remains enabled, including logout and link mutations.
        // The H2 console is denied above, so it needs no CSRF or frame-options exemption.
        // Gate terminals carry no session cookie: their authority is the X-Gate-Token
        // header, which a cross-site page cannot set, so CSRF adds nothing there.
        // The browser beacon is sent by sendBeacon, which cannot carry the header; it
        // changes nothing but adds a log row, which is all a forged one could do too.
        http.csrf(csrf -> csrf.ignoringRequestMatchers("/api/gates/**", "/ws/**", "/api/log/**"));
        // Before any authorisation decision: a session reflects the account as it is now.
        http.addFilterAfter(new AdminSessionCheck(accounts), SecurityContextHolderFilter.class);
        if (!clientId.trim().isEmpty() && !clientSecret.trim().isEmpty()) {
            ClientRegistration google = CommonOAuth2Provider.GOOGLE.getBuilder("google")
                .clientId(clientId).clientSecret(clientSecret)
                .redirectUri(baseUrl + "/login/oauth2/code/google").build();
            InMemoryClientRegistrationRepository registrations =
                new InMemoryClientRegistrationRepository(google);
            // Ask which account: a shared machine otherwise signs in as whoever Google
            // remembers, and the operator only finds out from the name in the header.
            DefaultOAuth2AuthorizationRequestResolver resolver =
                new DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization");
            resolver.setAuthorizationRequestCustomizer(request -> request
                .additionalParameters(parameters -> parameters.put("prompt", "select_account")));
            GoogleAccess access = new GoogleAccess(allowedDomains, allowedEmails);
            if (!access.configured()) {
                log.warn("Google sign-in is configured but GOOGLE_ALLOWED_DOMAINS and GOOGLE_ALLOWED_EMAILS "
                    + "are both empty, so every Google account will be refused.");
            }
            OidcUserService oidcUsers = new OidcUserService();
            OAuth2UserService<OidcUserRequest, OidcUser> allowListed = request -> {
                OidcUser user = oidcUsers.loadUser(request);
                boolean allowed = access.allows(user);
                ActivityLog.emit(Activity.of(Activity.AUTH, "google.login").ok(allowed)
                    .result(allowed ? null : "not_allowed").summary(user.getEmail())
                    .actor(allowed ? "ADMIN" : "ANON", "google:" + user.getSubject(), user.getEmail())
                    .put("email", user.getEmail()).put("domain", user.getClaimAsString("hd")).always());
                if (!allowed) {
                    throw new OAuth2AuthenticationException(new OAuth2Error(GOOGLE_DENIED,
                        "This Google account is not allowed to sign in here.", null));
                }
                return user;
            };
            http.oauth2Login(oauth -> oauth
                .clientRegistrationRepository(registrations)
                .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(resolver))
                .userInfoEndpoint(userInfo -> userInfo.oidcUserService(allowListed))
                .loginPage(baseUrl + "/login")
                .defaultSuccessUrl(baseUrl + "/", true)
                // A refused account is told so; anything else is a sign-in that failed.
                .failureHandler((request, response, error) -> {
                    ActivityLog.emit(Activity.of(Activity.AUTH, "google.login_failed")
                        .failed(error.getClass().getSimpleName()).summary(error.getMessage()).always());
                    response.sendRedirect(baseUrl + "/login?error="
                    + (error instanceof OAuth2AuthenticationException oauthError
                        && GOOGLE_DENIED.equals(oauthError.getError().getErrorCode())
                        ? "google-denied" : "google"));
                }));
        }
        return http.build();
    }
}
