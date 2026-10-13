package com.codebaseai.backend.service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Maps a streaming AI-service failure to a short, user-safe reason plus a
 * machine-readable {@code code} for the SSE {@code error} event.
 *
 * <p>Nothing from the provider's response body, tokens or stack traces ever
 * leaves the server: callers log the original exception with the project id
 * and request id and ship only this payload to the browser. The generic
 * message is the last resort when nothing matches.
 */
final class AiStreamErrors {

    static final String GENERIC =
            "The AI service could not complete this. Please try again.";
    private static final String UNREACHABLE = "AI service unreachable";
    private static final String TOO_MUCH_CODE =
            "This question touched too much code, try naming a file or folder";
    private static final String RATE_LIMITED = "Model rate limited, try again in %d second%s.";

    private AiStreamErrors() {
    }

    /**
     * Walk the cause chain (WebClient wraps connect and response failures)
     * and return the first matching {@code {message, code}} payload.
     */
    static Map<String, String> describe(Throwable error) {
        for (Throwable cause = error;
                cause != null && cause != cause.getCause();
                cause = cause.getCause()) {
            if (cause instanceof WebClientResponseException response) {
                return describeStatus(response);
            }
            if (cause instanceof WebClientRequestException
                    || cause instanceof java.net.ConnectException
                    || cause instanceof TimeoutException) {
                return of(UNREACHABLE, "ai_unreachable");
            }
            if (cause instanceof WebClientException) {
                // Dropped connection mid-stream or any other transport failure.
                return of(UNREACHABLE, "ai_unreachable");
            }
        }
        return of(GENERIC, "internal");
    }

    private static Map<String, String> describeStatus(WebClientResponseException response) {
        int status = response.getStatusCode().value();
        if (status == 429) {
            long wait = retryAfter(response);
            return of(String.format(RATE_LIMITED, wait, wait == 1 ? "" : "s"), "rate_limited");
        }
        if (status == 413) {
            return of(TOO_MUCH_CODE, "context_too_large");
        }
        if (status >= 500 || status == 404) {
            // The AI service is down, misrouted or answered with an error.
            return of(UNREACHABLE, "ai_unreachable");
        }
        return of(GENERIC, "upstream_error");
    }

    private static long retryAfter(WebClientResponseException response) {
        String header = response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        if (header != null) {
            try {
                return Math.max(1L, Long.parseLong(header.trim()));
            } catch (NumberFormatException ignored) {
                // Fall through to the default wait.
            }
        }
        return 30L;
    }

    static Map<String, String> of(String message, String code) {
        Map<String, String> payload = new HashMap<>(2);
        payload.put("message", message);
        payload.put("code", code);
        return payload;
    }
}
