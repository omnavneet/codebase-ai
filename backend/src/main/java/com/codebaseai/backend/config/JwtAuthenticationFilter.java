package com.codebaseai.backend.config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.codebaseai.backend.model.User;
import com.codebaseai.backend.repository.UserRepository;
import com.codebaseai.backend.service.JwtService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    
    private final JwtService jwtService;
    private final UserRepository userRepository;
    
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        
        // Get Authorization header
        String authHeader = request.getHeader("Authorization");
        
        // Check if header exists and starts with "Bearer "
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }
        
        // Extract token
        String token = authHeader.substring(7);
        
        try {
            // Validate token
            if (jwtService.isValidAccessToken(token)) {
                String userId = jwtService.extractUserId(token);

                // A valid signature is not enough: the account must still exist and the
                // token must have been issued under its current password (see JwtService).
                Optional<User> user = userRepository.findById(UUID.fromString(userId));
                if (user.isPresent() && jwtService.hasCurrentPasswordFingerprint(token, user.get())) {
                    // Create authentication object
                    UsernamePasswordAuthenticationToken authentication = 
                        new UsernamePasswordAuthenticationToken(
                            userId,
                            null,
                            new ArrayList<>()
                        );
                    
                    authentication.setDetails(
                        new WebAuthenticationDetailsSource().buildDetails(request)
                    );
                    
                    // Set authentication in context
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }
        } catch (Exception e) {
            // Token invalid, continue without authentication
            logger.error("JWT validation failed: " + e.getMessage());
        }
        
        filterChain.doFilter(request, response);
    }
}