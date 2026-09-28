package com.codebaseai.backend.service;

import java.time.LocalDateTime;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.codebaseai.backend.dto.AuthResponse;
import com.codebaseai.backend.dto.LoginRequest;
import com.codebaseai.backend.dto.RegisterRequest;
import com.codebaseai.backend.dto.RegisterResponse;
import com.codebaseai.backend.model.RefreshToken;
import com.codebaseai.backend.model.User;
import com.codebaseai.backend.repository.RefreshTokenRepository;
import com.codebaseai.backend.repository.UserRepository;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class AuthService {

    private static final String VERIFICATION_REQUIRED =
            "Email verification required. Please open the link we emailed you.";

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final CookieService cookieService;
    private final EmailVerificationService emailVerificationService;

    /**
     * Creates the account unverified and mails the confirmation link. No access or
     * refresh token is issued here: an unverified account cannot use the platform
     * until the link is opened.
     */
    public RegisterResponse register(RegisterRequest request) {
        String normalizedEmail = normalizeEmail(request.getEmail());

        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
        }

        User user = new User();
        user.setEmail(normalizedEmail);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setVerified(false);
        userRepository.save(user);

        // Sending runs inside this transaction on purpose: if the mail cannot be sent,
        // the exception rolls registration back instead of leaving an account that can
        // never be verified.
        emailVerificationService.issueAndSend(user);

        return new RegisterResponse("Check your email to verify your account.", user.getEmail());
    }

    public AuthResponse login(LoginRequest request, HttpServletResponse response) {
        String normalizedEmail = normalizeEmail(request.getEmail());

        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        if (!user.isVerified()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, VERIFICATION_REQUIRED);
        }

        return generateTokens(user, response);
    }

    public void verifyEmail(String token) {
        emailVerificationService.verify(token);
    }

    /**
     * Always reports the same generic outcome: whether the address exists, is already
     * verified, or was just emailed must not be observable from the outside.
     */
    public void resendVerification(String email) {
        User user = userRepository.findByEmail(normalizeEmail(email)).orElse(null);
        if (user == null || user.isVerified()) {
            return;
        }

        emailVerificationService.assertResendAllowed(user);
        emailVerificationService.issueAndSend(user);
    }

    public AuthResponse refresh(String refreshToken, HttpServletResponse response) {
        if (!jwtService.isTokenValid(refreshToken)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        }

        String tokenHash = jwtService.hashToken(refreshToken);
        RefreshToken storedToken = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token not found"));

        if (storedToken.isRevoked() || storedToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token revoked or expired");
        }

        storedToken.setRevoked(true);
        refreshTokenRepository.save(storedToken);

        User user = userRepository.findById(storedToken.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));

        if (!user.isVerified()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, VERIFICATION_REQUIRED);
        }

        return generateTokens(user, response);
    }
    
    public void logout(String refreshToken, HttpServletResponse response) {
        if (refreshToken != null) {
            String tokenHash = jwtService.hashToken(refreshToken);
            refreshTokenRepository.findByTokenHash(tokenHash).ifPresent(token -> {
                token.setRevoked(true);
                refreshTokenRepository.save(token);
            });
        }
        cookieService.clearRefreshTokenCookie(response);
    }
    
    private AuthResponse generateTokens(User user, HttpServletResponse response) {
        // Single choke point: an access/refresh token must never be minted for an
        // unverified account, whichever entry point (register, login, refresh) got here.
        if (!user.isVerified()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, VERIFICATION_REQUIRED);
        }

        String accessToken = jwtService.generateAccessToken(user.getId(), user.getEmail());
        String refreshToken = jwtService.generateRefreshToken(user.getId());

        refreshTokenRepository.deleteByUserId(user.getId());

        RefreshToken tokenEntity = new RefreshToken();
        tokenEntity.setUserId(user.getId());
        tokenEntity.setTokenHash(jwtService.hashToken(refreshToken));
        tokenEntity.setExpiresAt(LocalDateTime.now().plusSeconds(jwtService.getRefreshTokenValidity() / 1000));
        refreshTokenRepository.save(tokenEntity);

        cookieService.addRefreshTokenCookie(
                response, refreshToken, jwtService.getRefreshTokenValidity() / 1000);

        return new AuthResponse(accessToken, user.getEmail(), user.getId().toString());
    }

    private String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }
}