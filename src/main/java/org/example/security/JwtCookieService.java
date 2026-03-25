package org.example.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Optional HttpOnly JWT cookie (cross-site SPA needs {@code SameSite=None} + {@code Secure}).
 */
@Service
public class JwtCookieService {

    private final boolean enabled;
    private final String cookieName;
    private final boolean secure;
    private final String sameSite;
    private final int sessionDurationMinutes;

    public JwtCookieService(
            @Value("${security.auth.jwt-cookie-enabled:false}") boolean enabled,
            @Value("${security.auth.jwt-cookie-name:OP_JWT}") String cookieName,
            @Value("${security.auth.jwt-cookie-secure:false}") boolean secure,
            @Value("${security.auth.jwt-cookie-same-site:Lax}") String sameSite,
            @Value("${security.session.duration-minutes:45}") int sessionDurationMinutes) {
        this.enabled = enabled;
        this.cookieName = cookieName != null && !cookieName.isBlank() ? cookieName : "OP_JWT";
        this.secure = secure;
        this.sameSite = sameSite != null ? sameSite : "Lax";
        this.sessionDurationMinutes = Math.max(1, sessionDurationMinutes);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getCookieName() {
        return cookieName;
    }

    /** For logout when the client sends the JWT only in an HttpOnly cookie. */
    public String readTokenFromCookie(HttpServletRequest request) {
        if (!enabled || request.getCookies() == null) {
            return null;
        }
        for (Cookie c : request.getCookies()) {
            if (cookieName.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) {
                return c.getValue().trim();
            }
        }
        return null;
    }

    public void writeSessionCookie(HttpServletResponse response, String jwt) {
        if (!enabled || jwt == null || jwt.isBlank()) {
            return;
        }
        ResponseCookie cookie = ResponseCookie.from(cookieName, jwt)
                .httpOnly(true)
                .secure(secure)
                .path("/")
                .maxAge(Duration.ofMinutes(sessionDurationMinutes))
                .sameSite(sameSite)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public void clearSessionCookie(HttpServletResponse response) {
        if (!enabled) {
            return;
        }
        ResponseCookie cookie = ResponseCookie.from(cookieName, "")
                .httpOnly(true)
                .secure(secure)
                .path("/")
                .maxAge(0)
                .sameSite(sameSite)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
