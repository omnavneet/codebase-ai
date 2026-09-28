package com.codebaseai.backend.config;

import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import com.codebaseai.backend.model.User;
import com.codebaseai.backend.repository.UserRepository;

/**
 * Covers the endpoint set from the plan: every operation that creates data or spends
 * AI tokens must be refused for an unverified account, while plain reads stay open.
 */
@ExtendWith(MockitoExtension.class)
class VerifiedUserFilterTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private UserRepository userRepository;

    private VerifiedUserFilter filter;

    private final MockFilterChain chain = new MockFilterChain();

    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @BeforeEach
    void setUp() {
        filter = new VerifiedUserFilter(userRepository);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static User user(boolean verified) {
        User user = new User();
        user.setId(USER_ID);
        user.setEmail("user@example.com");
        user.setVerified(verified);
        return user;
    }

    private void authenticateAs(UUID userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, new ArrayList<>()));
    }

    private void runFilter(String method, String path) throws Exception {
        filter.doFilter(new MockHttpServletRequest(method, path), response, chain);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/projects",
            "/api/projects/11111111-1111-1111-1111-111111111111/upload",
            "/api/projects/11111111-1111-1111-1111-111111111111/search",
            "/api/projects/11111111-1111-1111-1111-111111111111/generated",
            "/api/projects/11111111-1111-1111-1111-111111111111/sessions",
            "/api/projects/11111111-1111-1111-1111-111111111111/agent/investigate",
            "/api/projects/11111111-1111-1111-1111-111111111111/agent/generate-docs",
            "/api/projects/11111111-1111-1111-1111-111111111111/agent/generate-readme",
            "/api/projects/11111111-1111-1111-1111-111111111111/agent/explain-code",
            "/api/projects/11111111-1111-1111-1111-111111111111/agent/debug",
            "/api/projects/11111111-1111-1111-1111-111111111111/agent/improve-code",
            "/api/sessions/22222222-2222-2222-2222-222222222222/messages",
            "/api/sessions/22222222-2222-2222-2222-222222222222/messages/stream"})
    void blocksUnverifiedUser(String path) throws Exception {
        authenticateAs(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(false)));

        runFilter("POST", path);

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("Email verification required"));
        assertNull(chain.getRequest(), "the request must not reach the controller");
    }

    @Test
    void allowsVerifiedUser() throws Exception {
        authenticateAs(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(true)));

        runFilter("POST", "/api/projects");

        assertNotNull(chain.getRequest());
        assertEquals(200, response.getStatus());
    }

    @Test
    void leavesReadRequestsOpen() throws Exception {
        authenticateAs(USER_ID);

        runFilter("GET", "/api/projects");

        assertNotNull(chain.getRequest());
    }

    @Test
    void leavesUnauthenticatedRequestsToSpringSecurity() throws Exception {
        runFilter("POST", "/api/projects");

        assertNotNull(chain.getRequest());
        assertEquals(200, response.getStatus());
    }

    @Test
    void leavesAnonymousAuthenticationToSpringSecurity() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "anonymous-key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        runFilter("POST", "/api/projects");

        assertNotNull(chain.getRequest());
    }
}
