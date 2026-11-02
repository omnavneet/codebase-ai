package com.codebaseai.backend.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.codebaseai.backend.model.User;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

class JwtServiceTest {

    private static final String SECRET = "test-secret-that-is-long-enough-for-hmac";

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secret", SECRET);
        ReflectionTestUtils.setField(jwtService, "accessTokenValidity", 60_000L);
        ReflectionTestUtils.setField(jwtService, "refreshTokenValidity", 60_000L);
    }

    private static User user(String passwordHash) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("user@example.com");
        user.setPasswordHash(passwordHash);
        return user;
    }

    @Test
    void accessTokenIsAcceptedAsAccessToken() {
        String token = jwtService.generateAccessToken(user("hash-a"));

        assertTrue(jwtService.isValidAccessToken(token));
        assertFalse(jwtService.isValidRefreshToken(token));
    }

    @Test
    void refreshTokenIsAcceptedOnlyAsRefreshToken() {
        String token = jwtService.generateRefreshToken(UUID.randomUUID());

        assertTrue(jwtService.isValidRefreshToken(token));
        assertFalse(jwtService.isValidAccessToken(token));
    }

    @Test
    void tokenWithoutTypeClaimIsRejectedByBothChecks() {
        String token = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000L))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertFalse(jwtService.isValidAccessToken(token));
        assertFalse(jwtService.isValidRefreshToken(token));
    }

    @Test
    void accessTokenMatchesTheUsersCurrentPasswordHash() {
        User user = user("hash-a");
        String token = jwtService.generateAccessToken(user);

        assertTrue(jwtService.hasCurrentPasswordFingerprint(token, user));
    }

    @Test
    void accessTokenStopsMatchingAfterThePasswordHashChanges() {
        User user = user("hash-a");
        String token = jwtService.generateAccessToken(user);

        user.setPasswordHash("hash-b");

        assertFalse(jwtService.hasCurrentPasswordFingerprint(token, user));
    }

    @Test
    void passwordFingerprintDoesNotContainThePasswordHashInClear() {
        String token = jwtService.generateAccessToken(user("hash-a"));
        String payload = new String(
                Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);

        assertFalse(payload.contains("hash-a"));
    }
}
