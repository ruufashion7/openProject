package org.example.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.auth.SessionInfo;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Requires {@code X-CSRF-Token} on mutating /api requests when {@link LoginCsrfProtectionService} is enabled.
 */
public class ApiMutatingCsrfFilter extends OncePerRequestFilter {

    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final LoginCsrfProtectionService loginCsrfProtectionService;

    public ApiMutatingCsrfFilter(LoginCsrfProtectionService loginCsrfProtectionService) {
        this.loginCsrfProtectionService = loginCsrfProtectionService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!loginCsrfProtectionService.isEnabled()) {
            return true;
        }
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getServletPath();
        if (path == null || !path.startsWith("/api")) {
            return true;
        }
        if ("POST".equalsIgnoreCase(request.getMethod()) && "/api/login".equals(path)) {
            return true;
        }
        if ("POST".equalsIgnoreCase(request.getMethod()) && "/api/logout".equals(path)) {
            return true;
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!MUTATING.contains(request.getMethod().toUpperCase())) {
            filterChain.doFilter(request, response);
            return;
        }
        SessionInfo session = RequestScopedSessionCache.getSession();
        if (session == null) {
            filterChain.doFilter(request, response);
            return;
        }
        String token = request.getHeader("X-CSRF-Token");
        if (!loginCsrfProtectionService.validate(session.userId(), token)) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"Invalid or missing CSRF token\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
