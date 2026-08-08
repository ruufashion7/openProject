package org.example.api;

import org.example.auth.AuthSessionService;
import org.example.auth.SessionInfo;
import org.example.auth.SessionPermissions;
import org.example.settings.CustomerCreditLimitService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class CustomerCreditLimitSettingsController {

    private final AuthSessionService authSessionService;
    private final CustomerCreditLimitService creditLimitService;

    public CustomerCreditLimitSettingsController(
            AuthSessionService authSessionService,
            CustomerCreditLimitService creditLimitService) {
        this.authSessionService = authSessionService;
        this.creditLimitService = creditLimitService;
    }

    @GetMapping("/settings/customer-credit-limits")
    public ResponseEntity<?> getCategoryLimits(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        SessionInfo session = authSessionService.validate(extractBearer(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessDetailsOrOutstanding(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(Map.of("limits", creditLimitService.getCategoryLimits()));
    }

    @PutMapping("/admin/settings/customer-credit-limits")
    public ResponseEntity<?> updateCategoryLimits(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody CategoryLimitsUpdateRequest request) {
        SessionInfo session = authSessionService.validate(extractBearer(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Unauthorized"));
        }
        if (!session.isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Forbidden", "message", "Admin only"));
        }
        try {
            Map<String, Double> updated = creditLimitService.updateCategoryLimits(
                    request != null ? request.limits() : null,
                    session.displayName());
            return ResponseEntity.ok(Map.of("success", true, "limits", updated));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid request", "message", e.getMessage()));
        }
    }

    private record CategoryLimitsUpdateRequest(Map<String, Double> limits) {
    }

    private static String extractBearer(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        return authHeader.substring(7).trim();
    }
}
