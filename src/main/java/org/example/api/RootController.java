package org.example.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Root URL for API-only deployment (Render, etc.). Avoids static-resource errors on GET /.
 */
@RestController
public class RootController {

    @GetMapping("/")
    public Map<String, String> root() {
        return Map.of(
                "service", "openProject API",
                "health", "/actuator/health",
                "api", "/api"
        );
    }
}
