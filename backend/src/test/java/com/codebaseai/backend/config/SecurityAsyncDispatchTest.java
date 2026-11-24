package com.codebaseai.backend.config;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.codebaseai.backend.model.User;
import com.codebaseai.backend.repository.UserRepository;
import com.codebaseai.backend.service.JwtService;

@WebMvcTest(SecurityAsyncDispatchTest.Controller.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, VerifiedUserFilter.class,
        InMemoryRateLimitFilter.class, SecurityAsyncDispatchTest.Controller.class})
class SecurityAsyncDispatchTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtService jwtService;
    @MockitoBean UserRepository users;

    @RestController
    static class Controller {
        @GetMapping("/api/test-async")
        Callable<String> async() { return () -> "finished"; }
    }

    @Test
    void asyncDispatchRetainsAuthentication() throws Exception {
        UUID id = UUID.randomUUID();
        User user = new User();
        user.setId(id);
        user.setVerified(true);
        when(jwtService.isValidAccessToken("valid")).thenReturn(true);
        when(jwtService.extractUserId("valid")).thenReturn(id.toString());
        when(jwtService.hasCurrentPasswordFingerprint("valid", user)).thenReturn(true);
        when(users.findById(id)).thenReturn(Optional.of(user));
        MvcResult result = mvc.perform(get("/api/test-async").header("Authorization", "Bearer valid"))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andExpect(content().string("finished"));
    }

    @Test
    void originalRequestStillRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/test-async")).andExpect(status().isUnauthorized());
    }
}
