package com.codebaseai.backend.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * One-way hashing for bearer secrets that are stored server-side (refresh tokens,
 * email-verification tokens). Both are long random strings, so a fast SHA-256
 * digest is the right tool: it leaves the stored value useless to anyone who reads
 * the database while still allowing an indexed lookup. Slow, salted password
 * hashes (BCrypt) are for low-entropy secrets and would only get in the way here.
 */
final class TokenHasher {

    private TokenHasher() {
    }

    static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
