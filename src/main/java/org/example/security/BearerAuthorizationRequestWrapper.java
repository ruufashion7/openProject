package org.example.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Exposes JWT from an HttpOnly cookie as {@code Authorization: Bearer ...} so existing controllers
 * keep using {@code @RequestHeader Authorization} unchanged.
 */
public final class BearerAuthorizationRequestWrapper extends HttpServletRequestWrapper {

    private final String authorizationValue;

    public BearerAuthorizationRequestWrapper(HttpServletRequest request, String bearerToken) {
        super(request);
        this.authorizationValue = "Bearer " + bearerToken;
    }

    @Override
    public String getHeader(String name) {
        if (name != null && "Authorization".equalsIgnoreCase(name)) {
            return authorizationValue;
        }
        return super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        if (name != null && "Authorization".equalsIgnoreCase(name)) {
            return Collections.enumeration(List.of(authorizationValue));
        }
        return super.getHeaders(name);
    }
}
