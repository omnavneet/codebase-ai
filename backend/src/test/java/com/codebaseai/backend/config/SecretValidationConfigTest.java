package com.codebaseai.backend.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecretValidationConfigTest {

    /** 44 ASCII characters (> 32 bytes) - stands in for a real HS256 key. */
    private static final String GOOD_JWT =
            "c29tZS1yYW5kb20tMjU2LWJpdC1kZXYtc2VjcmV0LTEyMzQ1Ng==";

    /** 20 ASCII characters (> 16 bytes) - stands in for a shared token. */
    private static final String GOOD_TOKEN = "a1b2c3d4e5f6g7h8i9j0";

    @Test
    void acceptsStrongSecrets() {
        assertDoesNotThrow(() -> new SecretValidationConfig(GOOD_JWT, GOOD_TOKEN));
    }

    @Test
    void rejectsMissingSecrets() {
        assertThrows(IllegalStateException.class, () -> new SecretValidationConfig("", GOOD_TOKEN));
        assertThrows(IllegalStateException.class, () -> new SecretValidationConfig("   ", GOOD_TOKEN));
        assertThrows(IllegalStateException.class, () -> new SecretValidationConfig(GOOD_JWT, ""));
        assertThrows(IllegalStateException.class, () -> new SecretValidationConfig(GOOD_JWT, null));
    }

    @Test
    void rejectsShortSecrets() {
        assertThrows(IllegalStateException.class, () -> new SecretValidationConfig("too-short-key", GOOD_TOKEN));
        assertThrows(IllegalStateException.class, () -> new SecretValidationConfig(GOOD_JWT, "short"));
    }

    @Test
    void rejectsWellKnownPlaceholders() {
        assertThrows(IllegalStateException.class, () -> new SecretValidationConfig("changeit", GOOD_TOKEN));
        assertThrows(IllegalStateException.class, () -> new SecretValidationConfig("CHANGEIT", GOOD_TOKEN));
        assertThrows(IllegalStateException.class,
                () -> new SecretValidationConfig(GOOD_JWT, "local-dev-internal-token"));
    }
}
