package org.example.api;

import org.example.auth.AuthSessionService;
import org.example.auth.SessionInfo;
import org.example.auth.SessionPermissions;
import org.example.bill.BillExtractResponse;
import org.example.bill.BillExtractService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/bills")
public class BillExtractController {

    private final AuthSessionService authSessionService;
    private final BillExtractService billExtractService;

    public BillExtractController(AuthSessionService authSessionService, BillExtractService billExtractService) {
        this.authSessionService = authSessionService;
        this.billExtractService = billExtractService;
    }

    @GetMapping("/extract/status")
    public ResponseEntity<?> status(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        SessionInfo session = requireSession(authHeader);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessBillExtract(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(billExtractService.status());
    }

    @PostMapping(value = "/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> extract(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam("files") MultipartFile[] files
    ) {
        SessionInfo session = requireSession(authHeader);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessBillExtract(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            List<MultipartFile> list = files == null ? List.of() : Arrays.asList(files);
            BillExtractResponse body = billExtractService.extract(list);
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message", e.getMessage()));
        }
    }

    private SessionInfo requireSession(String authHeader) {
        return authSessionService.validate(extractToken(authHeader));
    }

    private String extractToken(String authHeader) {
        if (authHeader == null || authHeader.isBlank()) {
            return null;
        }
        String t = authHeader.trim();
        if (t.regionMatches(true, 0, "Bearer ", 0, 7)) {
            t = t.substring(7).trim();
        }
        return t.isEmpty() ? null : t;
    }
}
