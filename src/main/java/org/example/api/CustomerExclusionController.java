package org.example.api;

import org.example.auth.AuthSessionService;
import org.example.auth.SessionInfo;
import org.example.auth.SessionPermissions;
import org.example.payment.CustomerExclusionService;
import org.example.payment.CustomerExclusionService.ExcludedCustomerView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/analytics/customers")
public class CustomerExclusionController {

    private final AuthSessionService authSessionService;
    private final CustomerExclusionService customerExclusionService;

    public CustomerExclusionController(
            AuthSessionService authSessionService,
            CustomerExclusionService customerExclusionService
    ) {
        this.authSessionService = authSessionService;
        this.customerExclusionService = customerExclusionService;
    }

    @GetMapping("/excluded")
    public ResponseEntity<?> listExcluded(
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessOutstandingPage(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(customerExclusionService.listExcluded());
    }

    @PostMapping("/exclude")
    public ResponseEntity<?> exclude(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody CustomerExcludeRequest request
    ) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canExcludeCustomer(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (request == null || request.customer() == null || request.customer().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Customer name is required"));
        }
        try {
            ExcludedCustomerView view = customerExclusionService.excludeByDisplayName(
                    request.customer().trim(),
                    session.displayName()
            );
            return ResponseEntity.ok(view);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/restore")
    public ResponseEntity<?> restore(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody CustomerRestoreRequest request
    ) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canExcludeCustomer(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (request == null || request.customerKey() == null || request.customerKey().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Customer key is required"));
        }
        try {
            ExcludedCustomerView view = customerExclusionService.restoreByCustomerKey(
                    request.customerKey().trim(),
                    session.displayName()
            );
            return ResponseEntity.ok(view);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
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

    private record CustomerExcludeRequest(String customer) {
    }

    private record CustomerRestoreRequest(String customerKey) {
    }
}
