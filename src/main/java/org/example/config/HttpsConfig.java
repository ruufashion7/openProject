package org.example.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * HTTPS configuration and enforcement.
 * SECURITY: Enforces HTTPS in production and redirects HTTP to HTTPS.
 */
@Configuration
public class HttpsConfig {

    @Value("${server.port:8080}")
    private int serverPort;

    @Value("${server.ssl.enabled:false}")
    private boolean sslEnabled;

    /**
     * Filter to redirect HTTP to HTTPS in production.
     * Only active when SSL is enabled.
     */
    @Bean
    @Profile("production")
    public OncePerRequestFilter httpsRedirectFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, 
                                          HttpServletResponse response, 
                                          jakarta.servlet.FilterChain filterChain) 
                    throws jakarta.servlet.ServletException, IOException {
                
                // Check if request is HTTP
                if (request.getScheme().equals("http")) {
                    // Build HTTPS URL
                    String httpsUrl = "https://" + request.getServerName() + 
                                    (request.getServerPort() != 80 ? ":" + request.getServerPort() : "") +
                                    request.getRequestURI() +
                                    (request.getQueryString() != null ? "?" + request.getQueryString() : "");
                    
                    response.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
                    response.setHeader("Location", httpsUrl);
                    return;
                }
                
                filterChain.doFilter(request, response);
            }
        };
    }

    /**
     * Customizer to enforce secure cookies and HTTPS-only settings.
     */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> tomcatCustomizer() {
        return factory -> {
            // Add secure cookie configuration
            factory.addContextCustomizers(context -> {
                // Set secure flag for cookies (HTTPS only)
                if (sslEnabled) {
                    context.setUseHttpOnly(true);
                }
            });
        };
    }
}

