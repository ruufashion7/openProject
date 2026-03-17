package org.example.api;

import org.example.auth.AuthSessionService;
import org.example.auth.SessionInfo;
import org.example.model.DashboardSummary;
import org.example.service.DashboardService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class DashboardController {

    private final DashboardService dashboardService;
    private final AuthSessionService authSessionService;

    public DashboardController(DashboardService dashboardService, AuthSessionService authSessionService) {
        this.dashboardService = dashboardService;
        this.authSessionService = authSessionService;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health(
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        // SECURITY: Health endpoint now requires authentication
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "timestamp", Instant.now().toString()
        ));
    }

    @GetMapping("/dashboard")
    public ResponseEntity<DashboardSummary> dashboard(
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(dashboardService.getSummary());
    }

    private String extractToken(String authHeader) {
        if (authHeader == null || authHeader.isBlank()) {
            return null;
        }
        String prefix = "Bearer ";
        if (authHeader.startsWith(prefix)) {
            return authHeader.substring(prefix.length()).trim();
        }
        return authHeader.trim();
    }
}

