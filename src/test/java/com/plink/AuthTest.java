package com.plink;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"plink.auth.google-client-id=test-client", "plink.auth.google-client-secret=test-secret"})
@AutoConfigureMockMvc
class AuthTest {
    @Autowired MockMvc mvc;

    @Test void anonymousSessionDoesNotExposeSecrets() throws Exception {
        mvc.perform(get("/api/auth/session"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.user").isEmpty())
            .andExpect(jsonPath("$.googleEnabled").value(true))
            .andExpect(jsonPath("$.csrfToken").isNotEmpty())
            .andExpect(content().string(not(containsString("test-secret"))));
    }

    @Test void authenticatedSessionOnlyReturnsProfile() throws Exception {
        mvc.perform(get("/api/auth/session").with(oidcLogin().idToken(token -> token
            .claim("name", "Demo User").claim("email", "demo@example.com"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.user.name").value("Demo User"))
            .andExpect(jsonPath("$.user.email").value("demo@example.com"))
            .andExpect(jsonPath("$.user.idToken").doesNotExist());
    }

    @Test void googleRedirectIncludesStateAndExpectedCallback() throws Exception {
        mvc.perform(get("/oauth2/authorization/google"))
            .andExpect(status().is3xxRedirection())
            .andExpect(header().string("Location", startsWith("https://accounts.google.com/")))
            .andExpect(header().string("Location", containsString("state=")))
            .andExpect(header().string("Location", containsString("nonce=")))
            .andExpect(header().string("Location", containsString("redirect_uri=http://localhost:3000/login/oauth2/code/google")));
    }

    @Test void unsolicitedCallbackCannotSignIn() throws Exception {
        mvc.perform(get("/login/oauth2/code/google").param("code", "fake").param("state", "fake"))
            .andExpect(redirectedUrl("http://localhost:3000/login?error=google"));
    }

    @Test void logoutRequiresCsrfAndInvalidatesSession() throws Exception {
        mvc.perform(post("/api/auth/logout")).andExpect(status().isForbidden());
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/logout").session(session).with(oidcLogin()).with(csrf()))
            .andExpect(status().isNoContent());
        assertTrue(session.isInvalid());
    }

    @Test void existingDemoWritesWorkWithCsrf() throws Exception {
        String body = "{\"originalUrl\":\"https://example.com\"}";
        mvc.perform(post("/api/links").contentType("application/json").content(body))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/links").with(oidcLogin()).with(csrf()).contentType("application/json").content(body))
            .andExpect(status().isCreated());
    }
}
