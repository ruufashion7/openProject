package org.example.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

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

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
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
            
            // Security headers
            .headers(headers -> headers
                .frameOptions(frame -> frame.deny())
                .contentTypeOptions(contentType -> {})
                .httpStrictTransportSecurity(hsts -> hsts
                    .maxAgeInSeconds(31536000)
                )
                .referrerPolicy(referrer -> referrer
                    .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)
                )
                .permissionsPolicy(permissions -> permissions
                    .policy("geolocation=(), microphone=(), camera=()")
                )
            );

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        
        // SECURITY: Origins from cors.allowed-origins (comma-separated). Set in production to your Vercel URL(s).
        List<String> origins = Arrays.stream(allowedOriginsConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
        if (origins.isEmpty()) {
            origins = Arrays.asList("http://localhost:4200", "http://127.0.0.1:4200");
        }
        configuration.setAllowedOrigins(origins);
        
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        
        // SECURITY: Only allow necessary headers
        configuration.setAllowedHeaders(Arrays.asList(
            "Authorization",
            "Content-Type",
            "X-Requested-With",
            "Accept",
            "Origin"
        ));
        
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
}

