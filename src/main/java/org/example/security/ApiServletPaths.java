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
}
