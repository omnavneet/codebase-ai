package com.codebaseai.backend.config;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.codebaseai.backend.model.User;
import com.codebaseai.backend.repository.UserRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * Defence in depth for the email-verification requirement. The primary control lives
 * at token-minting time (no token is ever issued to an unverified account); this
 * filter re-checks the endpoints that create data or spend AI tokens, so a token
 * issued before a flag change cannot reach them either.
 *
 * <p>Runs after {@link JwtAuthenticationFilter}, which means the request is already
 * authenticated here. Requests without authentication are passed through untouched so
 * Spring Security keeps answering 401 for them.
 */
@Component
@RequiredArgsConstructor
public class VerifiedUserFilter extends OncePerRequestFilter {

    static final String VERIFICATION_REQUIRED_MESSAGE =
            "Email verification required. Please verify your email address to use this feature.";

    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        if (!requiresVerifiedUser(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            filterChain.doFilter(request, response);
            return;
        }

        UUID userId;
        try {
            userId = UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            filterChain.doFilter(request, response);
            return;
        }

        Optional<User> user = userRepository.findById(userId);
        if (user.isEmpty() || user.get().isVerified()) {
            // Unknown user: keep the controller's existing 401/404 behaviour.
            filterChain.doFilter(request, response);
            return;
        }

        // Shaped like ApiErrorResponse so the SPA surfaces the server's message.
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"message\":\"" + VERIFICATION_REQUIRED_MESSAGE + "\"}");
    }

    /**
     * Endpoints that create or transform data ("at minimum" set from the plan): project
     * creation, ZIP upload, semantic search, docs export, chat and every agent tool.
     * Plain reads stay open - they are ownership-checked anyway, and are not what an
     * unverified account could be used to abuse.
     */
    private boolean requiresVerifiedUser(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) {
            return false;
        }

        String path = request.getRequestURI();
        if (path.equals("/api/projects")) {
            return true;
        }

        return path.matches("/api/projects/[^/]+/(upload|search|generated|sessions|agent/.*)")
                || path.matches("/api/sessions/[^/]+/messages(/stream)?");
    }
}
