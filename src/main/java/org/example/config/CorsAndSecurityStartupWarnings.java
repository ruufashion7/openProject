package org.example.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Logs high-signal misconfiguration warnings at startup.
 */
@Component
public class CorsAndSecurityStartupWarnings {

    private static final Logger log = LoggerFactory.getLogger(CorsAndSecurityStartupWarnings.class);

    private final Environment environment;
    private final String allowedOrigins;

    public CorsAndSecurityStartupWarnings(
            Environment environment,
            @Value("${cors.allowed-origins:}") String allowedOrigins) {
        this.environment = environment;
        this.allowedOrigins = allowedOrigins != null ? allowedOrigins : "";
    }

    @EventListener(ContextRefreshedEvent.class)
    public void onReady() {
        String[] profiles = environment.getActiveProfiles();
        boolean prodLike = Arrays.stream(profiles).anyMatch(p -> p.equalsIgnoreCase("prod")
                || p.equalsIgnoreCase("production"));
        if (prodLike && (allowedOrigins.contains("localhost") || allowedOrigins.contains("127.0.0.1"))) {
            log.warn("SECURITY: CORS allows localhost while prod-like profile is active ({}). "
                            + "Set CORS_ALLOWED_ORIGINS to real frontend origins only.",
                    String.join(",", profiles));
        }
        log.info("SECURITY: Schedule ./mvnw verify -Psecurity-dependencies and npm run audit:deps for dependency CVEs.");
    }
}
