package com.codebaseai.backend.service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.server.ResponseStatusException;

import com.codebaseai.backend.config.AppProperties;
import com.codebaseai.backend.model.User;
import com.codebaseai.backend.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {

    private static final int TOKEN_TTL_HOURS = 24;
    private static final String EMAIL = "user@example.com";

    @Mock
    private UserRepository userRepository;

    @Mock
    private JavaMailSender mailSender;

    private MailService mailService;

    private EmailVerificationService service;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        properties.setPublicUrl("http://localhost:5173");
        properties.getVerification().setTokenTtlHours(TOKEN_TTL_HOURS);
        properties.getVerification().setResendCooldownSeconds(60);
        mailService = new MailService(mailSender, properties);
        service = new EmailVerificationService(userRepository, mailService, properties);
    }

    private static User unverifiedUser() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail(EMAIL);
        return user;
    }

    private static String tokenFrom(String mailBody) {
        String rest = mailBody.substring(mailBody.indexOf("token=") + "token=".length());
        return rest.split("\\s")[0];
    }

    /** Returns the single verification message that was handed to the mail sender. */
    private SimpleMailMessage sentMessage() {
        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(sent.capture());
        return sent.getValue();
    }

    private String emailedToken() {
        SimpleMailMessage message = sentMessage();
        assertEquals(EMAIL, message.getTo()[0]);
        assertTrue(message.getText().contains("http://localhost:5173/auth/verify-email?token="));
        return tokenFrom(message.getText());
    }

    @Test
    void issueAndSendStoresOnlyTheHashOfTheEmailedToken() {
        User user = unverifiedUser();
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.issueAndSend(user);

        String rawToken = emailedToken();
        assertNotEquals(rawToken, user.getVerificationTokenHash());
        assertEquals(TokenHasher.sha256Hex(rawToken), user.getVerificationTokenHash());
        assertTrue(user.getVerificationTokenExpiresAt().isAfter(LocalDateTime.now().plusHours(23)));
        assertTrue(user.getVerificationTokenExpiresAt().isBefore(LocalDateTime.now().plusHours(25)));
        assertFalse(user.isVerified());
    }

    @Test
    void issuingANewTokenInvalidatesThePreviousOne() {
        User user = unverifiedUser();
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.issueAndSend(user);
        String firstHash = user.getVerificationTokenHash();

        service.issueAndSend(user);
        String secondHash = user.getVerificationTokenHash();

        ArgumentCaptor<SimpleMailMessage> messages = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, times(2)).send(messages.capture());

        assertNotEquals(firstHash, secondHash);
        assertEquals(TokenHasher.sha256Hex(tokenFrom(messages.getAllValues().get(1).getText())), secondHash);
    }

    @Test
    void verifyMarksTheAccountVerifiedAndClearsTheToken() {
        User user = unverifiedUser();
        user.setVerificationTokenHash(TokenHasher.sha256Hex("raw-token"));
        user.setVerificationTokenExpiresAt(LocalDateTime.now().plusHours(1));
        when(userRepository.findByVerificationTokenHash(TokenHasher.sha256Hex("raw-token")))
                .thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User verified = service.verify("raw-token");

        assertTrue(verified.isVerified());
        assertNull(verified.getVerificationTokenHash());
        assertNull(verified.getVerificationTokenExpiresAt());
    }

    @Test
    void verifyRejectsUnknownToken() {
        when(userRepository.findByVerificationTokenHash(any())).thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class, () -> service.verify("unknown-token"));

        assertEquals(400, exception.getStatusCode().value());
        verify(userRepository, never()).save(any());
    }

    @Test
    void verifyRejectsExpiredTokenWithoutVerifyingTheAccount() {
        User user = unverifiedUser();
        user.setVerificationTokenHash(TokenHasher.sha256Hex("raw-token"));
        user.setVerificationTokenExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(userRepository.findByVerificationTokenHash(TokenHasher.sha256Hex("raw-token")))
                .thenReturn(Optional.of(user));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class, () -> service.verify("raw-token"));

        assertEquals(400, exception.getStatusCode().value());
        assertFalse(user.isVerified());
        verify(userRepository, never()).save(any());
    }

    @Test
    void verifyRejectsBlankTokenWithoutHittingTheDatabase() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class, () -> service.verify("   "));

        assertEquals(400, exception.getStatusCode().value());
        verify(userRepository, never()).findByVerificationTokenHash(any());
    }

    @Test
    void resendWithinTheCooldownIsRejected() {
        User user = unverifiedUser();
        user.setVerificationTokenHash(TokenHasher.sha256Hex("raw-token"));
        // Issued ten seconds ago, so still inside the 60 second cooldown.
        user.setVerificationTokenExpiresAt(LocalDateTime.now().plusHours(TOKEN_TTL_HOURS).minusSeconds(10));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class, () -> service.assertResendAllowed(user));

        assertEquals(429, exception.getStatusCode().value());
    }

    @Test
    void resendIsAllowedOnceTheCooldownHasPassed() {
        User user = unverifiedUser();
        user.setVerificationTokenHash(TokenHasher.sha256Hex("raw-token"));
        user.setVerificationTokenExpiresAt(LocalDateTime.now().plusHours(TOKEN_TTL_HOURS).minusMinutes(5));

        service.assertResendAllowed(user);
    }

    @Test
    void resendIsAllowedWhenNoTokenIsPending() {
        service.assertResendAllowed(unverifiedUser());
    }

    @Test
    void mailFailureSurfacesAsServiceUnavailable() {
        User user = unverifiedUser();
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new MailSendException("smtp down"))
                .when(mailSender).send(any(SimpleMailMessage.class));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class, () -> service.issueAndSend(user));

        assertEquals(503, exception.getStatusCode().value());
    }
}
