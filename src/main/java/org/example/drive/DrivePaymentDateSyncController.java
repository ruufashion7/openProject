package org.example.drive;

import org.example.auth.AuthSessionService;
import org.example.auth.SessionInfo;
import org.example.auth.SessionPermissions;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/analytics/outstanding-due/drive-sync")
public class DrivePaymentDateSyncController {

    private final AuthSessionService authSessionService;
    private final DrivePaymentDateSyncService syncService;

    public DrivePaymentDateSyncController(
            AuthSessionService authSessionService,
            DrivePaymentDateSyncService syncService
    ) {
        this.authSessionService = authSessionService;
        this.syncService = syncService;
    }

    @GetMapping
    public ResponseEntity<DrivePaymentDateSyncResponse> status(
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessOutstandingPage(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(syncService.status());
    }

    @PostMapping
    public ResponseEntity<DrivePaymentDateSyncResponse> syncNow(
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canEditPaymentDate(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        DrivePaymentDateSyncResponse result = syncService.syncNow();
        return ResponseEntity.status(httpStatusFor(result)).body(result);
    }

    private static HttpStatus httpStatusFor(DrivePaymentDateSyncResponse result) {
        if (result.running() && "A Drive sync is already running.".equals(result.lastMessage())) {
            return HttpStatus.CONFLICT;
        }
        if ("failed".equals(result.lastStatus()) || "push-failed".equals(result.lastStatus())) {
            return HttpStatus.BAD_GATEWAY;
        }
        if (result.enabled() && !result.configured()) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        return HttpStatus.OK;
    }

    private String extractToken(String authHeader) {
        if (authHeader == null || authHeader.isBlank()) {
            return null;
        }
        String value = authHeader.trim();
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return value.substring(7).trim();
        }
        return value;
    }
}
