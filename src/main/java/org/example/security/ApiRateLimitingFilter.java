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
 * Per-IP cap on {@code /api/**} traffic (login, session, upload status/polls are exempt).
 * Redis when configured; otherwise a fixed in-memory window per JVM.
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
        String path = ApiServletPaths.normalizedServletPath(request);
        if (path.isEmpty() || !(path.startsWith("/api/") || "/api".equals(path))) {
            return true;
        }
        return ApiServletPaths.isApiRateLimitExempt(request.getMethod(), path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String clientIp = clientIp(request);
        if (!rateLimitingService.isApiRequestAllowed(clientIp)) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", String.valueOf(rateLimitingService.apiRetryAfterSeconds(clientIp)));
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
