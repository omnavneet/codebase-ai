package com.codebaseai.backend.service;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.Mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.codebaseai.backend.dto.AuthResponse;
import com.codebaseai.backend.dto.LoginRequest;
import com.codebaseai.backend.dto.RegisterRequest;
import com.codebaseai.backend.model.User;
import com.codebaseai.backend.repository.RefreshTokenRepository;
import com.codebaseai.backend.repository.UserRepository;

import jakarta.servlet.http.HttpServletResponse;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    private JwtService jwtService;

    private CookieService cookieService;

    @Mock
    private HttpServletResponse response;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        cookieService = new CookieService();
        ReflectionTestUtils.setField(jwtService, "secret", "test-secret-that-is-long-enough-for-hmac");
        ReflectionTestUtils.setField(jwtService, "accessTokenValidity", 60_000L);
        ReflectionTestUtils.setField(jwtService, "refreshTokenValidity", 60_000L);
        authService = new AuthService(
                userRepository,
                refreshTokenRepository,
                passwordEncoder,
                jwtService,
                cookieService);
    }

    @Test
    void loginRejectsWrongPassword() {
        LoginRequest request = new LoginRequest();
        request.setEmail("user@example.com");
        request.setPassword("wrong-password");

        User user = new User();
        user.setEmail("user@example.com");
        user.setPasswordHash("stored-hash");
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong-password", "stored-hash")).thenReturn(false);

        org.springframework.web.server.ResponseStatusException exception = assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
                () -> authService.login(request, response));
        assertEquals(401, exception.getStatusCode().value());
        verify(refreshTokenRepository, never()).deleteByUserId(any());
    }

    @Test
    void registerCreatesTokensForNewUser() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail(" User@Example.com ");
        request.setPassword("password-123");

        UUID userId = UUID.randomUUID();
        when(userRepository.existsByEmail("user@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password-123")).thenReturn("hashed-password");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(userId);
            return saved;
        });
        AuthResponse result = authService.register(request, response);

        org.junit.jupiter.api.Assertions.assertNotNull(result.getAccessToken());
        assertEquals("user@example.com", result.getEmail());
        verify(userRepository).save(any(User.class));
    }
}
