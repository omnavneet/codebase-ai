package com.codebaseai.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.ToString;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "users")
@Data
public class User {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    
    @Column(nullable = false, unique = true)
    private String email;
    
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;
    
    /**
     * False until the user opens the link mailed at registration. No access token
     * is minted for an unverified account, so this flag gates the whole platform.
     * Lombok exposes it as {@code isVerified()} / {@code setVerified(...)}.
     */
    @Column(name = "is_verified", nullable = false)
    private boolean verified;

    // A pending verification token is a secret until it is used: the hash must never
    // reach a log line (@ToString.Exclude) or an API response (@JsonIgnore).
    @JsonIgnore
    @ToString.Exclude
    @Column(name = "verification_token_hash")
    private String verificationTokenHash;

    @JsonIgnore
    @ToString.Exclude
    @Column(name = "verification_token_expires_at")
    private LocalDateTime verificationTokenExpiresAt;
    
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}