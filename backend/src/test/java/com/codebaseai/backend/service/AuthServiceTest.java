package com.codebaseai.backend.service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.codebaseai.backend.config.AppProperties;
import com.codebaseai.backend.dto.AuthResponse;
import com.codebaseai.backend.dto.LoginRequest;
import com.codebaseai.backend.dto.RegisterRequest;
import com.codebaseai.backend.dto.RegisterResponse;
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

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private HttpServletResponse response;

    private EmailVerificationService emailVerificationService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        JwtService jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secret", "test-secret-that-is-long-enough-for-hmac");
        ReflectionTestUtils.setField(jwtService, "accessTokenValidity", 60_000L);
        ReflectionTestUtils.setField(jwtService, "refreshTokenValidity", 60_000L);

        AppProperties properties = new AppProperties();
        properties.setPublicUrl("http://localhost:5173");

        MailService mailService = new MailService(mailSender, properties);
        emailVerificationService = new EmailVerificationService(userRepository, mailService, properties);
        authService = new AuthService(
                userRepository,
                refreshTokenRepository,
                passwordEncoder,
                jwtService,
                new CookieService(),
                emailVerificationService);
    }

    private static User user(boolean verified) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("user@example.com");
        user.setPasswordHash("stored-hash");
        user.setVerified(verified);
        return user;
    }

    /** Returns the raw token carried by the single verification email that went out. */
    private String sentVerificationToken() {
        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(sent.capture());
        String body = sent.getValue().getText();
        String rest = body.substring(body.indexOf("token=") + "token=".length());
        return rest.split("\\s")[0];
    }

    @Test
    void loginRejectsWrongPassword() {
        LoginRequest request = new LoginRequest();
        request.setEmail("user@example.com");
        request.setPassword("wrong-password");

        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user(true)));
        when(passwordEncoder.matches("wrong-password", "stored-hash")).thenReturn(false);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class, () -> authService.login(request, response));
        assertEquals(401, exception.getStatusCode().value());
        verify(refreshTokenRepository, never()).deleteByUserId(any());
    }

    @Test
    void loginRejectsUnverifiedAccountWithoutIssuingTokens() {
        LoginRequest request = new LoginRequest();
        request.setEmail("user@example.com");
        request.setPassword("password-123");

        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user(false)));
        when(passwordEncoder.matches("password-123", "stored-hash")).thenReturn(true);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class, () -> authService.login(request, response));
        assertEquals(403, exception.getStatusCode().value());
        // No refresh cookie and no refresh-token row: the account stays unusable.
        verify(response, never()).addCookie(any());
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void loginIssuesTokensForVerifiedAccount() {
        LoginRequest request = new LoginRequest();
        request.setEmail("user@example.com");
        request.setPassword("password-123");

        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user(true)));
        when(passwordEncoder.matches("password-123", "stored-hash")).thenReturn(true);

        AuthResponse result = authService.login(request, response);

        assertNotNull(result.getAccessToken());
        assertEquals("user@example.com", result.getEmail());
        verify(response).addCookie(any());
        verify(refreshTokenRepository).save(any());
    }

    @Test
    void registerCreatesUnverifiedAccountAndMailsTheLink() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail(" User@Example.com ");
        request.setPassword("password-123");

        when(userRepository.existsByEmail("user@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password-123")).thenReturn("hashed-password");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });

        RegisterResponse result = authService.register(request);

        assertEquals("user@example.com", result.getEmail());
        assertEquals("Check your email to verify your account.", result.getMessage());

        // Insert + storing the token hash.
        ArgumentCaptor<User> savedUsers = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(2)).save(savedUsers.capture());
        User stored = savedUsers.getValue();
        assertFalse(stored.isVerified());
        assertNotNull(stored.getVerificationTokenHash());
        assertEquals(TokenHasher.sha256Hex(sentVerificationToken()), stored.getVerificationTokenHash());

        // Registration must not sign the user in.
        verify(response, never()).addCookie(any());
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void registerSurfacesMailFailuresSoTheCallerCanRollBack() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("user@example.com");
        request.setPassword("password-123");

        when(userRepository.existsByEmail("user@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password-123")).thenReturn("hashed-password");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        org.mockito.Mockito.doThrow(new MailSendException("smtp down"))
                .when(mailSender).send(any(SimpleMailMessage.class));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class, () -> authService.register(request));

        assertEquals(503, exception.getStatusCode().value());
    }

    @Test
    void resendVerificationStaysSilentForUnknownAddress() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        authService.resendVerification("nobody@example.com");

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void resendVerificationStaysSilentForAlreadyVerifiedAccount() {
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user(true)));

        authService.resendVerification("user@example.com");

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void resendVerificationSendsANewLinkAndInvalidatesTheOldOne() {
        User unverified = user(false);
        unverified.setVerificationTokenHash(TokenHasher.sha256Hex("old-token"));
        // Issued five minutes ago: past the 60 second cooldown.
        unverified.setVerificationTokenExpiresAt(LocalDateTime.now().plusHours(24).minusMinutes(5));
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(unverified));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        authService.resendVerification(" user@example.com ");

        assertNotEquals(TokenHasher.sha256Hex("old-token"), unverified.getVerificationTokenHash());
        assertEquals(TokenHasher.sha256Hex(sentVerificationToken()), unverified.getVerificationTokenHash());
    }

    @Test
    void verifyEmailMarksTheAccountVerified() {
        User unverified = user(false);
        unverified.setVerificationTokenHash(TokenHasher.sha256Hex("raw-token"));
        unverified.setVerificationTokenExpiresAt(LocalDateTime.now().plusHours(1));
        when(userRepository.findByVerificationTokenHash(TokenHasher.sha256Hex("raw-token")))
                .thenReturn(Optional.of(unverified));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        authService.verifyEmail("raw-token");

        assertTrue(unverified.isVerified());
        assertNull(unverified.getVerificationTokenHash());
    }
}
