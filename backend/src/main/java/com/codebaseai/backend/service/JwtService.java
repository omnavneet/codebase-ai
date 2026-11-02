package com.codebaseai.backend.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.codebaseai.backend.model.User;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Date;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class JwtService {

    // Access and refresh tokens share one signing key, so the type claim is what keeps
    // a long-lived refresh token from being accepted as an API credential.
    private static final String TOKEN_TYPE_CLAIM = "typ";
    private static final String ACCESS_TOKEN_TYPE = "access";
    private static final String REFRESH_TOKEN_TYPE = "refresh";

    // Ties an access token to the password hash it was issued under. Changing the password
    // changes the hash and therefore this value, so outstanding access tokens stop matching.
    private static final String PASSWORD_FINGERPRINT_CLAIM = "pwv";
    
    @Value("${app.jwt.secret}")
    private String secret;
    
    @Value("${app.jwt.access-token-validity-ms}")
    private long accessTokenValidity;
    
    @Value("${app.jwt.refresh-token-validity-ms}")
    private long refreshTokenValidity;
    
    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
    
    public String generateAccessToken(User user) {
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim(TOKEN_TYPE_CLAIM, ACCESS_TOKEN_TYPE)
                .claim(PASSWORD_FINGERPRINT_CLAIM, passwordFingerprint(user.getPasswordHash()))
                .id(UUID.randomUUID().toString())   // unique jti so tokens are never identical
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessTokenValidity))
                .signWith(getSigningKey())
                .compact();
    }

    public String generateRefreshToken(UUID userId) {
        return Jwts.builder()
                .subject(userId.toString())
                .claim(TOKEN_TYPE_CLAIM, REFRESH_TOKEN_TYPE)
                .id(UUID.randomUUID().toString())   // unique jti so hashes never collide
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + refreshTokenValidity))
                .signWith(getSigningKey())
                .compact();
    }
    
    public String extractUserId(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }
    
    public boolean isValidAccessToken(String token) {
        return hasTokenType(token, ACCESS_TOKEN_TYPE);
    }

    public boolean isValidRefreshToken(String token) {
        return hasTokenType(token, REFRESH_TOKEN_TYPE);
    }

    /**
     * True when the access token was issued under the user's current password hash.
     * Fails closed: any parse or claim problem returns false.
     */
    public boolean hasCurrentPasswordFingerprint(String token, User user) {
        try {
            Claims claims = Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
            String claimed = claims.get(PASSWORD_FINGERPRINT_CLAIM, String.class);
            if (claimed == null) {
                return false;
            }
            return MessageDigest.isEqual(
                    claimed.getBytes(StandardCharsets.UTF_8),
                    passwordFingerprint(user.getPasswordHash()).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasTokenType(String token, String expectedType) {
        try {
            Claims claims = Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
            return expectedType.equals(claims.get(TOKEN_TYPE_CLAIM, String.class));
        } catch (Exception e) {
            return false;
        }
    }

    // Keyed with the signing secret, so the claim reveals nothing about the stored hash
    // to anyone who can read the token but does not hold the secret.
    private String passwordFingerprint(String passwordHash) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(getSigningKey());
            return HexFormat.of().formatHex(mac.doFinal(passwordHash.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    public long getRefreshTokenValidity() {
        return refreshTokenValidity;
    }

    public String hashToken(String token) {
        // Only the SHA-256 digest is stored; see TokenHasher.
        return TokenHasher.sha256Hex(token);
    }
}
