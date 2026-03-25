package org.example.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;

/**
 * Rejects oversized non-multipart bodies on /api/** to reduce DoS surface (Tomcat allows very large POSTs for uploads).
 */
public class RequestBodySizeLimitFilter extends OncePerRequestFilter {

    private final long maxBodyBytes;
    private final boolean enabled;

    public RequestBodySizeLimitFilter(boolean enabled, long maxBodyBytes) {
        this.enabled = enabled;
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!enabled || maxBodyBytes <= 0) {
            return true;
        }
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getServletPath();
        if (path == null || !path.startsWith("/api")) {
            return true;
        }
        if (isUploadStylePath(path)) {
            return true;
        }
        String ct = request.getContentType();
        if (ct != null && ct.toLowerCase(Locale.ROOT).startsWith("multipart/")) {
            return true;
        }
        return false;
    }

    private static boolean isUploadStylePath(String path) {
        return path.contains("/upload") || path.contains("bulk-upload");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String method = request.getMethod();
        if (!"POST".equalsIgnoreCase(method) && !"PUT".equalsIgnoreCase(method) && !"PATCH".equalsIgnoreCase(method)) {
            filterChain.doFilter(request, response);
            return;
        }
        long len = request.getContentLengthLong();
        if (len > 0 && len > maxBodyBytes) {
            response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"Request body too large\"}");
            return;
        }
        if (len <= 0) {
            filterChain.doFilter(new LimitedBodyHttpServletRequestWrapper(request, maxBodyBytes), response);
            return;
        }
        filterChain.doFilter(request, response);
    }
}
