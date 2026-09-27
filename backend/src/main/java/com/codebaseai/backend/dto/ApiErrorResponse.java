package com.codebaseai.backend.dto;

import java.time.Instant;
import java.util.List;

/**
 * Uniform error body produced by {@code GlobalExceptionHandler}. The SPA reads
 * {@code message} for user-facing text, so that field must always be populated
 * with something safe to display.
 */
public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        List<FieldViolation> fieldErrors) {

    public record FieldViolation(String field, String message) {
    }
}
