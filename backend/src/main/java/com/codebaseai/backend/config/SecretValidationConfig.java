package com.codebaseai.backend.config;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fails startup when a security-critical secret is missing, is a well-known
 * placeholder, or is too short for its algorithm.
 *
 * <p>The backend image ships application.properties.example, where secrets are
 * plain ${ENV} references without fallback values, so a forgotten variable
 * already breaks placeholder resolution. This validator closes the second hole:
 * a deployment that explicitly configures a weak or copy-pasted tutorial value
 * would otherwise run silently with a signing key an attacker may already know
 * - every access token could then be forged for any user.
 */
@Component
public class SecretValidationConfig {

    /** HS256 requires at least 256 bits of key material (32 ASCII bytes). */
    private static final int MIN_JWT_SECRET_BYTES = 32;

    /** Shared secret guarding the service-to-service AI API; 16 bytes minimum. */
    private static final int MIN_INTERNAL_TOKEN_BYTES = 16;

    /** Copy-pasted tutorial values that must never reach a running deployment. */
    private static final Set<String> PLACEHOLDER_SECRETS = Set.of(
            "changeit", "secret", "changeme", "your-secret-here", "replace-me",
            "local-dev-internal-token");

    public SecretValidationConfig(
            @Value("${app.jwt.secret:}") String jwtSecret,
            @Value("${app.ai-service.internal-token:}") String internalToken) {
        validate("app.jwt.secret", jwtSecret, MIN_JWT_SECRET_BYTES);
        validate("app.ai-service.internal-token", internalToken, MIN_INTERNAL_TOKEN_BYTES);
    }

    /** Package-private and static so the rules can be unit-tested directly. */
    static void validate(String property, String value, int minBytes) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("'" + property + "' is not configured. Export the "
                    + "matching environment variable with a random value (e.g. "
                    + "'openssl rand -base64 32'); starting with a missing signing secret is "
                    + "never acceptable.");
        }
        if (PLACEHOLDER_SECRETS.contains(value.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("'" + property + "' is set to the well-known "
                    + "placeholder value '" + value + "'. Generate a fresh random secret.");
        }
        if (value.getBytes(StandardCharsets.UTF_8).length < minBytes) {
            throw new IllegalStateException("'" + property + "' must be at least " + minBytes
                    + " bytes of random data; the configured value is shorter.");
        }
    }
}
