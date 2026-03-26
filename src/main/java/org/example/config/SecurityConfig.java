package org.example.config;

import org.example.auth.AuthSessionService;
import org.example.security.ApiBearerAuthenticationFilter;
import org.example.security.ApiMutatingCsrfFilter;
import org.example.security.ApiRateLimitingFilter;
import org.example.security.JwtCookieService;
import org.example.security.LoginCsrfProtectionService;
import org.example.security.RateLimitingService;
import org.example.security.RequestBodySizeLimitFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Security configuration for the application.
 * Provides security headers, CORS configuration, and request filtering.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Comma-separated list of allowed CORS origins (e.g. for Vercel: https://your-app.vercel.app).
     * Defaults to localhost for development.
     */
    @Value("${cors.allowed-origins:http://localhost:4200,http://127.0.0.1:4200}")
    private String allowedOriginsConfig;

    /**
     * Optional glob patterns (comma-separated), e.g. {@code https://*.vercel.app} for all Vercel preview + production URLs.
     * Env: {@code CORS_ALLOWED_ORIGIN_PATTERNS}. Used together with {@code cors.allowed-origins}.
     */
    @Value("${cors.allowed-origin-patterns:}")
    private String allowedOriginPatternsConfig;

    @Bean
    public ApiRateLimitingFilter apiRateLimitingFilter(
            RateLimitingService rateLimitingService,
            @Value("${security.rate-limit.api.enabled:true}") boolean apiRateLimitEnabled) {
        return new ApiRateLimitingFilter(rateLimitingService, apiRateLimitEnabled);
    }

    @Bean
    public RequestBodySizeLimitFilter requestBodySizeLimitFilter(
            @Value("${security.request.body-limit.enabled:true}") boolean enabled,
            @Value("${security.request.body-limit.max-bytes:10485760}") long maxBytes) {
        return new RequestBodySizeLimitFilter(enabled, maxBytes);
    }

    @Bean
    public ApiBearerAuthenticationFilter apiBearerAuthenticationFilter(
            AuthSessionService authSessionService,
            JwtCookieService jwtCookieService) {
        return new ApiBearerAuthenticationFilter(
                authSessionService,
                jwtCookieService.isEnabled(),
                jwtCookieService.getCookieName());
    }

    @Bean
    public ApiMutatingCsrfFilter apiMutatingCsrfFilter(LoginCsrfProtectionService loginCsrfProtectionService) {
        return new ApiMutatingCsrfFilter(loginCsrfProtectionService);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ApiRateLimitingFilter apiRateLimitingFilter,
            RequestBodySizeLimitFilter requestBodySizeLimitFilter,
            ApiBearerAuthenticationFilter apiBearerAuthenticationFilter,
            ApiMutatingCsrfFilter apiMutatingCsrfFilter) throws Exception {
        http
            .addFilterBefore(apiRateLimitingFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(requestBodySizeLimitFilter, ApiRateLimitingFilter.class)
            .addFilterAfter(apiBearerAuthenticationFilter, RequestBodySizeLimitFilter.class)
            .addFilterAfter(apiMutatingCsrfFilter, ApiBearerAuthenticationFilter.class)
            // Disable CSRF for API (using token-based auth instead)
            // Note: For production, consider implementing CSRF token validation
            .csrf(csrf -> csrf.disable())
            
            // Configure CORS
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            
            // Stateless session management (using token-based auth)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            
            // Configure request authorization
            // NOTE: Authentication is handled at controller level using custom token validation
            // Spring Security is used here for security headers, CORS, and session management only
            .authorizeHttpRequests(auth -> auth
                // All requests are permitted at Spring Security level
                // Controller-level auth handles token validation for each endpoint
                .requestMatchers("/**").permitAll()
            )
            
            // Security headers (block lambda avoids chaining .contentSecurityPolicy off PermissionsPolicyConfigurer)
            .headers(headers -> {
                headers.frameOptions(frame -> frame.deny());
                headers.contentTypeOptions(contentType -> {});
                headers.httpStrictTransportSecurity(hsts -> hsts.maxAgeInSeconds(31536000));
                headers.referrerPolicy(referrer -> referrer
                        .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN));
                headers.permissionsPolicy(permissions -> permissions
                        .policy("geolocation=(), microphone=(), camera=()"));
                headers.contentSecurityPolicy(csp -> csp
                        .policyDirectives("default-src 'none'; base-uri 'none'; form-action 'none'"));
            });

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        
        // Request origins are compared after trimming a trailing slash (see CorsConfiguration#checkOrigin).
        // Normalize configured values the same way so https://app.com/ in env still matches the browser's https://app.com
        List<String> origins = Arrays.stream(allowedOriginsConfig.split(","))
                .map(SecurityConfig::normalizeConfiguredOrigin)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
        List<String> originPatterns = Arrays.stream(allowedOriginPatternsConfig.split(","))
                .map(SecurityConfig::normalizeConfiguredPattern)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
        if (origins.isEmpty() && originPatterns.isEmpty()) {
            origins = new ArrayList<>(Arrays.asList("http://localhost:4200", "http://127.0.0.1:4200"));
        }
        if (!origins.isEmpty()) {
            configuration.setAllowedOrigins(origins);
        }
        if (!originPatterns.isEmpty()) {
            configuration.setAllowedOriginPatterns(originPatterns);
        }
        
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD"));
        
        // Preflight echoes requested header names; "*" avoids 403 when browsers or extensions add headers
        // (e.g. trace/baggage) to Access-Control-Request-Headers after the first attempt.
        configuration.setAllowedHeaders(List.of(CorsConfiguration.ALL));
        
        // SECURITY: Don't expose sensitive headers
        configuration.setExposedHeaders(Arrays.asList(
            "Content-Type",
            "Content-Length"
        ));
        
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L); // Cache preflight for 1 hour
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        
        return source;
    }

    private static String normalizeConfiguredOrigin(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.length() >= 2) {
            char a = s.charAt(0);
            char b = s.charAt(s.length() - 1);
            if ((a == '"' && b == '"') || (a == '\'' && b == '\'')) {
                s = s.substring(1, s.length() - 1).trim();
            }
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** Patterns are not slash-stripped (would break e.g. {@code https://*.vercel.app}). */
    private static String normalizeConfiguredPattern(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.length() >= 2) {
            char a = s.charAt(0);
            char b = s.charAt(s.length() - 1);
            if ((a == '"' && b == '"') || (a == '\'' && b == '\'')) {
                s = s.substring(1, s.length() - 1).trim();
            }
        }
        return s;
    }
}

