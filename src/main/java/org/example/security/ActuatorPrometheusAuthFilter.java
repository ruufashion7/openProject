package org.example.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Optional bearer token for {@code /actuator/prometheus} when {@code metrics.scrape-token} is set (production).
 * Disabled in dev when the property is empty.
 */
public class ActuatorPrometheusAuthFilter extends OncePerRequestFilter {

    private final String scrapeToken;

    public ActuatorPrometheusAuthFilter(String scrapeToken) {
        this.scrapeToken = scrapeToken == null ? "" : scrapeToken.trim();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (scrapeToken.isEmpty()) {
            return true;
        }
        String path = request.getRequestURI();
        return path == null || !path.endsWith("/actuator/prometheus");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String auth = request.getHeader(HttpHeaders.AUTHORIZATION);
        String expected = "Bearer " + scrapeToken;
        if (expected.equals(auth)) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"Invalid metrics scrape token\"}");
    }
}
