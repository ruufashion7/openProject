package org.example.api;

import org.example.auth.AuthSessionService;
import org.example.auth.SessionInfo;
import org.example.payment.CustomerMasterDedupeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Admin maintenance for {@code customer_master}.
 */
@RestController
@RequestMapping("/api/admin/customer-master")
public class CustomerMasterAdminController {

    private static final Logger logger = LoggerFactory.getLogger(CustomerMasterAdminController.class);

    private final AuthSessionService authSessionService;
    private final CustomerMasterDedupeService dedupeService;

    public CustomerMasterAdminController(
            AuthSessionService authSessionService,
            CustomerMasterDedupeService dedupeService) {
        this.authSessionService = authSessionService;
        this.dedupeService = dedupeService;
    }

    /**
     * Merges rows that share the same {@code customerKey} (e.g. String vs ObjectId {@code _id}),
     * then creates a unique index on {@code customerKey}. Admin only.
     */
    @PostMapping("/dedupe")
    public ResponseEntity<?> dedupe(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
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
            CustomerMasterDedupeService.DedupeResult result = dedupeService.dedupeAndEnsureUniqueIndex();
            logger.info("customer_master dedupe: groupsMerged={}, removed={}, distinctKeys={}",
                    result.duplicateGroupsMerged(), result.documentsRemoved(), result.distinctCustomerKeys());
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "duplicateGroupsMerged", result.duplicateGroupsMerged(),
                    "documentsRemoved", result.documentsRemoved(),
                    "distinctCustomerKeys", result.distinctCustomerKeys(),
                    "message", "Deduped customer_master and ensured unique index on customerKey"
            ));
        } catch (Exception e) {
            logger.error("customer_master dedupe failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Dedupe failed", "message", e.getMessage()));
        }
    }

    private static String extractBearer(String authHeader) {
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
