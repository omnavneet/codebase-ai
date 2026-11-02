package com.codebaseai.backend.config;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import com.codebaseai.backend.model.User;
import com.codebaseai.backend.repository.UserRepository;
import com.codebaseai.backend.service.JwtService;

class JwtAuthenticationFilterTest {

    private JwtService jwtService;
    private UserRepository userRepository;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secret", "test-secret-that-is-long-enough-for-hmac");
        ReflectionTestUtils.setField(jwtService, "accessTokenValidity", 60_000L);
        ReflectionTestUtils.setField(jwtService, "refreshTokenValidity", 60_000L);
        userRepository = mock(UserRepository.class);
        filter = new JwtAuthenticationFilter(jwtService, userRepository);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static User user(String passwordHash) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("user@example.com");
        user.setPasswordHash(passwordHash);
        user.setVerified(true);
        return user;
    }

    private Authentication authenticateWithBearer(String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/projects");
        request.addHeader("Authorization", "Bearer " + token);
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @Test
    void accessTokenAuthenticatesTheUser() throws Exception {
        User user = user("hash-a");
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        String token = jwtService.generateAccessToken(user);

        Authentication authentication = authenticateWithBearer(token);

        assertEquals(user.getId().toString(), authentication.getName());
    }

    @Test
    void refreshTokenDoesNotAuthenticateAsBearer() throws Exception {
        String refreshToken = jwtService.generateRefreshToken(UUID.randomUUID());

        assertNull(authenticateWithBearer(refreshToken));
    }

    @Test
    void accessTokenIssuedBeforePasswordChangeIsRejected() throws Exception {
        User user = user("hash-a");
        String token = jwtService.generateAccessToken(user);

        user.setPasswordHash("hash-b");
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        assertNull(authenticateWithBearer(token));
    }

    @Test
    void accessTokenForDeletedUserIsRejected() throws Exception {
        User user = user("hash-a");
        String token = jwtService.generateAccessToken(user);
        when(userRepository.findById(user.getId())).thenReturn(Optional.empty());

        assertNull(authenticateWithBearer(token));
    }
}
