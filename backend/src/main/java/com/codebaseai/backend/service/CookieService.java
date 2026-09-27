package com.codebaseai.backend.service;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class CookieService {
    
    /**
     * Marks the refresh cookie as HTTPS-only. Must be true wherever the service
     * is reachable over TLS; false is only acceptable for plain-HTTP local
     * development (see app.cookie.secure in application.properties).
     */
    @Value("${app.cookie.secure:false}")
    private boolean secureCookie;
    
    /**
     * @param maxAgeSeconds cookie lifetime, supplied by the caller from the
     *        refresh-token validity so the two lifetimes cannot drift apart.
     */
    public void addRefreshTokenCookie(HttpServletResponse response, String refreshToken, long maxAgeSeconds) {
        Cookie cookie = new Cookie("refresh_token", refreshToken);
        applyCookieAttributes(cookie);
        cookie.setMaxAge((int) Math.min(maxAgeSeconds, Integer.MAX_VALUE));
        response.addCookie(cookie);
    }

    public void clearRefreshTokenCookie(HttpServletResponse response) {
        Cookie cookie = new Cookie("refresh_token", "");
        applyCookieAttributes(cookie);
        cookie.setMaxAge(0); // Expire immediately
        response.addCookie(cookie);
    }

    private void applyCookieAttributes(Cookie cookie) {
        cookie.setHttpOnly(true);
        cookie.setSecure(secureCookie);
        cookie.setAttribute("SameSite", "Lax");
        cookie.setPath("/");
    }
}