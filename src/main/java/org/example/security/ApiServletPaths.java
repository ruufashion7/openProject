package org.example.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Consistent {@link HttpServletRequest#getServletPath()} handling for /api routes (trailing slashes, etc.).
 */
public final class ApiServletPaths {

    private ApiServletPaths() {
    }

    public static String normalizedServletPath(HttpServletRequest request) {
        return normalizeTrailingSlash(request.getServletPath());
    }

    public static String normalizeTrailingSlash(String path) {
        if (path == null || path.length() <= 1) {
            return path != null ? path : "";
        }
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    public static boolean isLoginPost(String method, String normalizedPath) {
        return "POST".equalsIgnoreCase(method) && "/api/login".equals(normalizedPath);
    }

    public static boolean isLogoutPost(String method, String normalizedPath) {
        return "POST".equalsIgnoreCase(method) && "/api/logout".equals(normalizedPath);
    }

    /**
     * Cheap authenticated GETs the SPA polls on a timer or on every welcome load.
     * Counting them in the global per-IP cap locked users out of Invoice / Details / Outstanding.
     */
    public static boolean isApiRateLimitExempt(String method, String normalizedPath) {
        if (normalizedPath == null || normalizedPath.isEmpty()) {
            return false;
        }
        if ("/api/login".equals(normalizedPath)) {
            return true;
        }
        if ("/api/session".equals(normalizedPath)
                || "/api/uploads/status".equals(normalizedPath)
                || "/api/upload/state".equals(normalizedPath)) {
            return true;
        }
        return "GET".equalsIgnoreCase(method) && normalizedPath.startsWith("/api/upload/jobs/");
    }
}
