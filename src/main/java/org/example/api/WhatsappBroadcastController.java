package org.example.api;

import org.example.auth.AuthSessionService;
import org.example.auth.SessionInfo;
import org.example.auth.SessionPermissions;
import org.example.whatsapp.WhatsappBroadcastService;
import org.example.whatsapp.dto.BroadcastBatchResponse;
import org.example.whatsapp.dto.BroadcastBatchSummaryResponse;
import org.example.whatsapp.dto.CreateBroadcastRequest;
import org.example.whatsapp.dto.UpdateRecipientRequest;
import org.example.whatsapp.dto.WaLinkResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/whatsapp/broadcasts")
public class WhatsappBroadcastController {

    private static final Logger logger = LoggerFactory.getLogger(WhatsappBroadcastController.class);

    private final AuthSessionService authSessionService;
    private final WhatsappBroadcastService broadcastService;

    public WhatsappBroadcastController(AuthSessionService authSessionService, WhatsappBroadcastService broadcastService) {
        this.authSessionService = authSessionService;
        this.broadcastService = broadcastService;
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody CreateBroadcastRequest body) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessWhatsappBroadcast(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Forbidden"));
        }
        try {
            return ResponseEntity.ok(broadcastService.create(session, body));
        } catch (IllegalArgumentException e) {
            logger.debug("Create broadcast validation: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessWhatsappBroadcast(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        List<BroadcastBatchSummaryResponse> rows = broadcastService.listSummaries(session);
        return ResponseEntity.ok(rows);
    }

    @GetMapping("/{batchId}")
    public ResponseEntity<?> get(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable String batchId) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessWhatsappBroadcast(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            BroadcastBatchResponse r = broadcastService.get(session, batchId);
            return ResponseEntity.ok(r);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{batchId}/recipients/{recipientId}/wa-link")
    public ResponseEntity<?> waLink(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable String batchId,
            @PathVariable String recipientId,
            @RequestParam(name = "markOpened", defaultValue = "true") boolean markOpened) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessWhatsappBroadcast(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            WaLinkResponse r = broadcastService.getWaLink(session, batchId, recipientId, markOpened);
            return ResponseEntity.ok(r);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PatchMapping("/{batchId}/recipients/{recipientId}")
    public ResponseEntity<?> patchRecipient(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable String batchId,
            @PathVariable String recipientId,
            @RequestBody UpdateRecipientRequest body) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessWhatsappBroadcast(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            return ResponseEntity.ok(broadcastService.updateRecipient(session, batchId, recipientId, body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private static String extractToken(String authHeader) {
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
