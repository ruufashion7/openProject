package org.example.api;

import org.example.auth.AuthSessionService;
import org.example.auth.SessionInfo;
import org.example.auth.SessionPermissions;
import org.example.payment.CustomerRetentionService;
import org.example.payment.CustomerRetentionService.RetainedCustomerView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/analytics/customers")
public class CustomerRetentionController {

    private final AuthSessionService authSessionService;
    private final CustomerRetentionService customerRetentionService;

    public CustomerRetentionController(
            AuthSessionService authSessionService,
            CustomerRetentionService customerRetentionService
    ) {
        this.authSessionService = authSessionService;
        this.customerRetentionService = customerRetentionService;
    }

    @GetMapping("/retained")
    public ResponseEntity<?> listRetained(
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        // Customer Details also needs status for Ignore / Retain actions
        if (!SessionPermissions.canAccessDetailsOrOutstanding(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(customerRetentionService.listRetained());
    }

    @PostMapping("/retain")
    public ResponseEntity<?> retain(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody CustomerRetainRequest request
    ) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canRetainCustomer(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (request == null || request.customer() == null || request.customer().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Customer name is required"));
        }
        try {
            RetainedCustomerView view = customerRetentionService.retainByDisplayName(
                    request.customer().trim(),
                    session.displayName()
            );
            return ResponseEntity.ok(view);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/unretain")
    public ResponseEntity<?> unretain(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody CustomerUnretainRequest request
    ) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canRetainCustomer(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (request == null || request.customerKey() == null || request.customerKey().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Customer key is required"));
        }
        try {
            RetainedCustomerView view = customerRetentionService.unretainByCustomerKey(
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

    private record CustomerRetainRequest(String customer) {
    }

    private record CustomerUnretainRequest(String customerKey) {
    }
}
