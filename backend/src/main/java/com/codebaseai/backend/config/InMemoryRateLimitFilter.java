package com.codebaseai.backend.config;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        int limit = limitFor(request.getRequestURI());
        if (limit == 0 || allow(request, limit)) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", "60");
        response.setContentType("application/json");
        response.getWriter().write("{\"message\":\"Too many requests. Please try again later.\"}");
    }

    private int limitFor(String path) {
        if (AUTH_PATHS.contains(path)) {
            return AUTH_LIMIT;
        }
        if (path.matches("/api/projects/[^/]+/agent/.*")
                || path.matches("/api/projects/[^/]+/search")) {
            return AI_LIMIT;
        }
        return 0;
    }

    private boolean allow(HttpServletRequest request, int limit) {
        String key = keyFor(request);
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
        return window.count <= limit;
    }

    private String keyFor(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getName() != null && !authentication.getName().isBlank()) {
            return "user:" + authentication.getName() + ":" + request.getRequestURI();
        }
        return "ip:" + request.getRemoteAddr() + ":" + request.getRequestURI();
    }

    private record Window(long startedAt, int count) {
    }
}
