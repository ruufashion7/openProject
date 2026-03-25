package org.example.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Per-IP sliding-window cap on {@code /api/**} traffic (excluding {@code /api/login}, which uses {@link RateLimitingService}).
 * In-memory only — not shared across multiple app instances; use a gateway or Redis-backed limiter for strict production quotas.
 * Registered only from {@link org.example.config.SecurityConfig} (not a {@code @Component}) to avoid double registration.
 */
public class ApiRateLimitingFilter extends OncePerRequestFilter {

    private final RateLimitingService rateLimitingService;
    private final boolean apiRateLimitEnabled;

    public ApiRateLimitingFilter(RateLimitingService rateLimitingService, boolean apiRateLimitEnabled) {
        this.rateLimitingService = rateLimitingService;
        this.apiRateLimitEnabled = apiRateLimitEnabled;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        if (!apiRateLimitEnabled) {
            return true;
        }
        String path = request.getRequestURI();
        if (path == null || !path.startsWith("/api/")) {
            return true;
        }
        // Login has dedicated username/IP policy in AuthController
        if (path.equals("/api/login") || path.endsWith("/api/login")) {
            return true;
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String clientIp = clientIp(request);
        if (!rateLimitingService.isApiRequestAllowed(clientIp)) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"Too many requests. Try again later.\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        String xReal = request.getHeader("X-Real-IP");
        if (xReal != null && !xReal.isBlank()) {
            return xReal.trim();
        }
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "unknown";
    }
}
