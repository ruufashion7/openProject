package org.example.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.auth.AuthSessionService;
import org.example.auth.SessionInfo;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Requires a valid Bearer JWT (header or HttpOnly cookie) for {@code /api/**} except login/logout.
 * Optionally injects {@link BearerAuthorizationRequestWrapper} so controllers keep reading {@code Authorization}.
 */
public class ApiBearerAuthenticationFilter extends OncePerRequestFilter {

    private final AuthSessionService authSessionService;
    private final boolean jwtCookieEnabled;
    private final String jwtCookieName;

    public ApiBearerAuthenticationFilter(
            AuthSessionService authSessionService,
            boolean jwtCookieEnabled,
            String jwtCookieName) {
        this.authSessionService = authSessionService;
        this.jwtCookieEnabled = jwtCookieEnabled;
        this.jwtCookieName = jwtCookieName != null ? jwtCookieName : "OP_JWT";
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getServletPath();
        if (path == null || !path.startsWith("/api")) {
            return true;
        }
        if (isPublicApiPath(request.getMethod(), path)) {
            return true;
        }
        return false;
    }

    private static boolean isPublicApiPath(String method, String path) {
        if ("POST".equalsIgnoreCase(method) && "/api/login".equals(path)) {
            return true;
        }
        if ("POST".equalsIgnoreCase(method) && "/api/logout".equals(path)) {
            return true;
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String fromHeader = extractBearerToken(request.getHeader("Authorization"));
        String fromCookie = jwtCookieEnabled ? readCookieValue(request, jwtCookieName) : null;
        String token = fromHeader != null ? fromHeader : fromCookie;

        if (token == null || token.isBlank()) {
            writeUnauthorized(response);
            return;
        }

        SessionInfo session = authSessionService.validate(token);
        if (session == null) {
            writeUnauthorized(response);
            return;
        }

        RequestScopedSessionCache.set(token, session);

        HttpServletRequest toDispatch = request;
        if (fromHeader == null && fromCookie != null) {
            toDispatch = new BearerAuthorizationRequestWrapper(request, token);
        }

        try {
            filterChain.doFilter(toDispatch, response);
        } finally {
            RequestScopedSessionCache.clear();
        }
    }

    private static String extractBearerToken(String authHeader) {
        if (authHeader == null || authHeader.isBlank()) {
            return null;
        }
        String prefix = "Bearer ";
        if (authHeader.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return authHeader.substring(prefix.length()).trim();
        }
        return null;
    }

    private static String readCookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }

    private static void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"Authentication required\"}");
    }
}
