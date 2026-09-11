package com.plink;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

// Isolated from ./.env: the suite must not depend on whichever origin, secret or
// face service a developer happens to have configured locally.
@SpringBootTest(properties = {"spring.config.import=","plink.auth.google-client-id=", "plink.auth.google-client-secret=",
    "plink.auth.base-url=https://lyuni.ddak.app", "spring.datasource.url=jdbc:h2:mem:auth-disabled",
    // An HTTPS origin must carry a real ticket secret; see TicketStartupCheckTest.
    "plink.ticket.token-secret=test-only-secret"})
@AutoConfigureMockMvc
class AuthDisabledTest {
    @Autowired MockMvc mvc;

    @Test void deploymentOriginCanUseTheApi() throws Exception {
        mvc.perform(options("/api/auth/logout")
                .header("Origin", "https://lyuni.ddak.app")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "X-CSRF-TOKEN"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", "https://lyuni.ddak.app"))
            .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
        mvc.perform(options("/api/auth/logout")
                .header("Origin", "https://untrusted.example")
                .header("Access-Control-Request-Method", "POST"))
            .andExpect(status().isForbidden());
    }

    @Test void missingCredentialsKeepTheDemoAvailable() throws Exception {
        mvc.perform(get("/api/auth/session"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.googleEnabled").value(false))
            .andExpect(jsonPath("$.user").isEmpty())
            .andExpect(jsonPath("$.csrfToken").isNotEmpty());
        mvc.perform(get("/api/links")).andExpect(status().isUnauthorized());
        mvc.perform(get("/oauth2/authorization/google")).andExpect(status().isNotFound());
    }
}
