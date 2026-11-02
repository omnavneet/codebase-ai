package com.codebaseai.backend.config;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Small fixed-window limiter for the single-instance portfolio deployment.
 * Replace the in-memory store with Redis when multiple backend instances share traffic.
 */
@Component
public class InMemoryRateLimitFilter extends OncePerRequestFilter {

    private static final int AUTH_LIMIT = 5;
    private static final int AI_LIMIT = 20;
    private static final long WINDOW_MILLIS = 60_000L;

    /** Password entry, sign-up and verification-mail requests share the tight limit. */
    private static final Set<String> AUTH_PATHS = Set.of(
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/resend-verification");

    /** Mirrors the chat message routes accepted by VerifiedUserFilter; keep them in sync. */
    private static final Pattern CHAT_MESSAGE_PATH = Pattern.compile(
            "/api/projects/[^/]+/conversations(/[^/]+)?/messages(/stream)?");

    /**
     * One quota per route family. The key deliberately omits the request URI so that
     * opening more projects or conversations does not multiply the quota.
     */
    private enum Bucket {
        AUTH(AUTH_LIMIT), CHAT(AI_LIMIT), AGENT(AI_LIMIT), SEARCH(AI_LIMIT);

        final int limit;

        Bucket(int limit) {
            this.limit = limit;
        }
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Bucket bucket = bucketFor(request);
        long[] retryAfterSeconds = new long[] {0};
        if (bucket == null || allow(request, bucket, retryAfterSeconds)) {
            filterChain.doFilter(request, response);
            return;
        }

        long seconds = Math.max(1, retryAfterSeconds[0]);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        // Real countdown so the client can say "Try again in N seconds" instead
        // of guessing. Clamped to >= 1 so a client never retries instantly.
        response.setHeader("Retry-After", String.valueOf(seconds));
        response.setContentType("application/json");
        response.getWriter().write("{\"message\":\"Too many requests. Please try again in "
                + seconds + " seconds.\"}");
    }

    private Bucket bucketFor(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (AUTH_PATHS.contains(path)) {
            return Bucket.AUTH;
        }
        if ("POST".equals(request.getMethod()) && CHAT_MESSAGE_PATH.matcher(path).matches()) {
            return Bucket.CHAT;
        }
        if (path.matches("/api/projects/[^/]+/agent/.*")) {
            return Bucket.AGENT;
        }
        if (path.matches("/api/projects/[^/]+/search")) {
            return Bucket.SEARCH;
        }
        return null;
    }

    private boolean allow(HttpServletRequest request, Bucket bucket, long[] retryAfterSecondsOut) {
        String key = keyFor(request, bucket);
        long now = Instant.now().toEpochMilli();
        Window window = windows.compute(key, (ignored, current) -> {
            if (current == null || now - current.startedAt >= WINDOW_MILLIS) {
                return new Window(now, 1);
            }
            return new Window(current.startedAt, current.count + 1);
        });

        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(entry -> now - entry.getValue().startedAt >= WINDOW_MILLIS);
        }

        if (window.count > bucket.limit) {
            // Seconds until this fixed window resets, so the caller can tell
            // the client exactly how long to wait.
            long elapsed = now - window.startedAt;
            retryAfterSecondsOut[0] = Math.max(1, (WINDOW_MILLIS - elapsed + 999) / 1000);
            return false;
        }
        return true;
    }

    private String keyFor(HttpServletRequest request, Bucket bucket) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getName() != null && !authentication.getName().isBlank()) {
            return "user:" + authentication.getName() + ":" + bucket.name();
        }
        return "ip:" + request.getRemoteAddr() + ":" + bucket.name();
    }

    private record Window(long startedAt, int count) {
    }
}
