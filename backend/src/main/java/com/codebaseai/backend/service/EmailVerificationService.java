package com.codebaseai.backend.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;

import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.codebaseai.backend.config.AppProperties;
import com.codebaseai.backend.model.User;
import com.codebaseai.backend.repository.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * Owns the whole "prove you own this mailbox" mechanism: token generation, the
 * hash that gets persisted, expiry, single use, resend throttling and the email
 * itself. Only the hash is ever stored; the raw token exists solely inside the
 * email handed to the user, and is never logged.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class EmailVerificationService {

    /** 256 bits of entropy: guessing a live token is not feasible. */
    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final MailService mailService;
    private final AppProperties properties;

    /**
     * Issues a fresh token, replacing any previous one (which is exactly what stops
     * an older link from still working), and mails the confirmation link.
     *
     * <p>Deliberately runs inside the caller's transaction: if the mail cannot be
     * sent, the exception rolls the whole operation back instead of leaving behind
     * an account that can never be verified.
     */
    public void issueAndSend(User user) {
        String rawToken = generateToken();
        user.setVerificationTokenHash(TokenHasher.sha256Hex(rawToken));
        user.setVerificationTokenExpiresAt(LocalDateTime.now()
                .plusHours(properties.getVerification().getTokenTtlHours()));
        userRepository.save(user);

        try {
            mailService.sendVerificationEmail(
                    user.getEmail(),
                    buildVerificationUrl(rawToken),
                    properties.getVerification().getTokenTtlHours());
        } catch (MailException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "We could not send the verification email. Please try again in a moment.", e);
        }
    }

    /**
     * Consumes a verification token: marks the account verified and clears the token
     * so the link cannot be replayed.
     */
    public User verify(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The verification link is missing its token.");
        }

        User user = userRepository.findByVerificationTokenHash(TokenHasher.sha256Hex(rawToken))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "This verification link is invalid or has already been used."));

        LocalDateTime expiresAt = user.getVerificationTokenExpiresAt();
        if (expiresAt == null || expiresAt.isBefore(LocalDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "This verification link has expired. Please request a new one.");
        }

        user.setVerified(true);
        user.setVerificationTokenHash(null);
        user.setVerificationTokenExpiresAt(null);
        return userRepository.save(user);
    }

    /**
     * Refuses to send another email within {@code app.verification.resend-cooldown-seconds}
     * of the previous one, which keeps one address from being mail-bombed from many
     * IPs. The remaining lifetime of the pending token tells us when it was issued,
     * so the cooldown needs no extra state and survives restarts.
     */
    public void assertResendAllowed(User user) {
        LocalDateTime expiresAt = user.getVerificationTokenExpiresAt();
        if (expiresAt == null) {
            return;
        }

        LocalDateTime issuedAt = expiresAt.minusHours(properties.getVerification().getTokenTtlHours());
        LocalDateTime allowedAt = issuedAt.plusSeconds(properties.getVerification().getResendCooldownSeconds());
        if (LocalDateTime.now().isBefore(allowedAt)) {
            long waitSeconds = Duration.between(LocalDateTime.now(), allowedAt).getSeconds() + 1;
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "A verification email was already sent. Please wait " + waitSeconds
                            + " seconds before requesting another one.");
        }
    }

    private String buildVerificationUrl(String rawToken) {
        String baseUrl = properties.getPublicUrl();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        // The link points at the SPA route, which then calls the API: that keeps mail
        // scanners (which prefetch links) from consuming a single-use token.
        return baseUrl + "/auth/verify-email?token=" + rawToken;
    }

    private String generateToken() {
        byte[] tokenBytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(tokenBytes);
        // URL-safe base64 without padding: the token travels inside a query string.
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    }
}
