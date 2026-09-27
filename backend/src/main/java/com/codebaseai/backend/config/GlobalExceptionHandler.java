package com.codebaseai.backend.config;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.reactive.function.client.WebClientException;
import org.springframework.web.server.ResponseStatusException;

import com.codebaseai.backend.dto.ApiErrorResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;

/**
 * Turns every uncaught exception into the same JSON body so the SPA can show a
 * real message ({@code ApiErrorResponse.message}) instead of a bare status code.
 *
 * <p>Mapping rules: {@link ErrorResponse} implementations (Spring's own MVC
 * exceptions and {@link ResponseStatusException}) keep their status; anything
 * else becomes a 500 whose message is deliberately generic so internal details
 * never leak to clients.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String GENERIC_ERROR = "An unexpected error occurred. Please try again.";

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> handleResponseStatus(
            ResponseStatusException ex, HttpServletRequest request) {
        if (ex.getStatusCode().is5xxServerError()) {
            log.error("{} {} failed", request.getMethod(), request.getRequestURI(), ex);
        }
        String message = ex.getReason() != null ? ex.getReason() : "Request failed";
        return build(ex.getStatusCode(), message, request, List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ApiErrorResponse.FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiErrorResponse.FieldViolation(error.getField(), error.getDefaultMessage()))
                .toList();
        String message = violations.stream()
                .map(violation -> violation.field() + ": " + violation.message())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST,
                message.isBlank() ? "Request validation failed" : message, request, violations);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {
        String message = ex.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST,
                message.isBlank() ? "Request validation failed" : message, request, List.of());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Invalid value for '" + ex.getName() + "'", request, List.of());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableBody(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Malformed or missing request body", request, List.of());
    }

    @ExceptionHandler(WebClientException.class)
    public ResponseEntity<ApiErrorResponse> handleAiServiceFailure(
            WebClientException ex, HttpServletRequest request) {
        log.error("AI service call failed for {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.BAD_GATEWAY,
                "The AI service did not answer successfully. Please try again.", request, List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleAnythingElse(Exception ex, HttpServletRequest request) {
        // Many Spring MVC exceptions (unmapped path, wrong method, missing
        // parameter, ...) are ErrorResponses: keep their status so a 404 cannot
        // silently become a 500, and only hide 5xx details.
        if (ex instanceof ErrorResponse errorResponse) {
            HttpStatusCode status = errorResponse.getStatusCode();
            if (status.is5xxServerError()) {
                log.error("{} {} failed", request.getMethod(), request.getRequestURI(), ex);
                return build(status, GENERIC_ERROR, request, List.of());
            }
            return build(status, ex.getMessage(), request, List.of());
        }

        log.error("{} {} failed", request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, GENERIC_ERROR, request, List.of());
    }

    private ResponseEntity<ApiErrorResponse> build(
            HttpStatusCode status,
            String message,
            HttpServletRequest request,
            List<ApiErrorResponse.FieldViolation> fieldErrors) {
        HttpStatus resolved = HttpStatus.resolve(status.value());
        String reason = resolved != null ? resolved.getReasonPhrase() : "Error";
        return ResponseEntity.status(status).body(new ApiErrorResponse(
                Instant.now(),
                status.value(),
                reason,
                message == null ? reason : message,
                request.getRequestURI(),
                fieldErrors));
    }
}
