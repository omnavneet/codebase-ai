package com.codebaseai.backend.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {
    
    @Value("${app.jwt.secret}")
    private String secret;
    
    @Value("${app.jwt.access-token-validity-ms}")
    private long accessTokenValidity;
    
    @Value("${app.jwt.refresh-token-validity-ms}")
    private long refreshTokenValidity;
    
    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
    
    public String generateAccessToken(UUID userId, String email) {
        return Jwts.builder()
                .subject(userId.toString())
                .claim("email", email)
                .id(UUID.randomUUID().toString())   // unique jti so tokens are never identical
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessTokenValidity))
                .signWith(getSigningKey())
                .compact();
    }

    public String generateRefreshToken(UUID userId) {
        return Jwts.builder()
                .subject(userId.toString())
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
    
    public boolean isTokenValid(String token) {
        try {
            Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token);
            return true;
        } catch (Exception e) {
            return false;
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