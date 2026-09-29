package com.codebaseai.backend.service;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class CookieService {
    
    /**
     * Marks the refresh cookie as HTTPS-only. Defaults to TRUE so any
     * deployment that forgets the setting stays safe; only plain-HTTP local
     * development should opt out (APP_COOKIE_SECURE=false, as the dev
     * docker-compose.yml and application.properties do).
     */
    @Value("${app.cookie.secure:true}")
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
        // Only the AuthController endpoints (/api/auth/refresh, /api/auth/logout)
        // ever read this cookie, so keep it off every other API request.
        cookie.setPath("/api/auth");
    }
}