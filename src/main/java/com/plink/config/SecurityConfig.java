package com.plink.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.boot.autoconfigure.security.servlet.PathRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
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
        http.authorizeRequests(auth -> auth
                .antMatchers("/api/links/s/**").permitAll()
                .antMatchers("/api/links", "/api/links/**").authenticated()
                .antMatchers("/h2-console/**").denyAll()
                .anyRequest().permitAll())
            .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, error) -> response.sendError(401)))
            .cors().and()
            .requestCache().disable()
            .formLogin().disable()
            .httpBasic().disable()
            .logout(logout -> logout.logoutUrl("/api/auth/logout")
                .invalidateHttpSession(true).clearAuthentication(true)
                .deleteCookies("JSESSIONID")
                .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)));
        http.csrf().ignoringRequestMatchers(PathRequest.toH2Console());
        http.headers().frameOptions().sameOrigin();
        // CSRF remains enabled, including logout and link mutations.
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
