package org.example.api;

import org.example.auth.AuthSessionService;
import org.example.auth.SessionInfo;
import org.example.auth.SessionPermissions;
import org.example.creditrisk.CreditDecisionAuditDocument;
import org.example.creditrisk.CreditPaymentEventUpload;
import org.example.creditrisk.CustomerLedgerExcelUploadValidation;
import org.example.creditrisk.CreditRiskEngineConfigDocument;
import org.example.creditrisk.CreditRiskOrderExcelService;
import org.example.creditrisk.CreditRiskService;
import org.example.creditrisk.CreditRiskSnapshotDocument;
import org.example.creditrisk.CustomerRiskSummaryResponse;
import org.example.creditrisk.OrderDecisionResult;
import org.example.payment.PaymentDateOverride;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/credit-risk")
public class CreditRiskController {

    private final AuthSessionService authSessionService;
    private final CreditRiskService creditRiskService;
    private final CreditRiskOrderExcelService orderExcelService;

    public CreditRiskController(
            AuthSessionService authSessionService,
            CreditRiskService creditRiskService,
            CreditRiskOrderExcelService orderExcelService
    ) {
        this.authSessionService = authSessionService;
        this.creditRiskService = creditRiskService;
        this.orderExcelService = orderExcelService;
    }

    @GetMapping("/customers")
    public ResponseEntity<?> listCustomers(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "bucket", required = false) String bucket
    ) {
        SessionInfo session = requireSession(authHeader);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessCreditRisk(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        List<CreditRiskSnapshotDocument> list = bucket != null && !bucket.isBlank()
                ? creditRiskService.listActionQueue(bucket)
                : creditRiskService.listSnapshots();
        return ResponseEntity.ok(list);
    }

    @GetMapping("/insights/committee")
    public ResponseEntity<?> committeeSummary(
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        SessionInfo session = requireSession(authHeader);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessCreditRisk(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(creditRiskService.committeeSummary());
    }

    @GetMapping("/insights/collect-today")
    public ResponseEntity<?> collectToday(
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        SessionInfo session = requireSession(authHeader);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessCreditRisk(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(creditRiskService.collectToday());
    }

    @GetMapping("/customers/limit-history")
    public ResponseEntity<?> limitHistory(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam("customerKey") String customerKey
    ) {
        SessionInfo session = requireSession(authHeader);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessCreditRisk(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (customerKey == null || customerKey.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "customerKey is required"));
        }
        return ResponseEntity.ok(creditRiskService.limitHistory(customerKey));
    }

    @GetMapping("/queue")
    public ResponseEntity<?> actionQueue(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "bucket", defaultValue = "ALL") String bucket
    ) {
        SessionInfo session = requireSession(authHeader);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessCreditRisk(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(creditRiskService.listActionQueue(bucket));
    }

    @PostMapping("/snapshots/rebuild")
    public ResponseEntity<?> rebuildSnapshots(
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        SessionInfo session = requireSession(authHeader);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canEditCreditRiskConfig(session)
                && !SessionPermissions.canAccessCreditRisk(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(creditRiskService.rebuildSnapshots());
    }

    @PostMapping("/customers/summary")
    public ResponseEntity<?> customerSummary(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody CustomerLookupRequest request
    ) {
        SessionInfo session = requireSession(authHeader);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessCreditRisk(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String q = resolveQuery(request);
        if (q == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "customer or phone is required"));
        }
        Optional<CustomerRiskSummaryResponse> summary = creditRiskService.summary(q);
        if (summary.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Customer not found"));
        }
        return ResponseEntity.ok(summary.get());
    }

    /**
     * OMS-ready evaluate: pass {@code referenceId} + {@code reserveExposure=true} to hold exposure.
     */
    @PostMapping("/orders/evaluate")
    public ResponseEntity<?> evaluateOrder(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody EvaluateOrderRequest request
    ) {
        SessionInfo session = requireSession(authHeader);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessCreditRisk(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String q = request != null && request.customer() != null && !request.customer().isBlank()
                ? request.customer()
                : (request != null ? request.customerKey() : null);
        if (q == null || q.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "customer or customerKey is required"));
        }
        if (request.orderAmount() == null || request.orderAmount() < 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "orderAmount is required"));
        }
        try {
            OrderDecisionResult result = creditRiskService.evaluate(
                    q,
                    request.orderAmount(),
                    request.referenceId(),
                    Boolean.TRUE.equals(request.reserveExposure()),
                    session.displayName()
            );
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping(value = "/orders/evaluate-excel", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> evaluateOrderExcel(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "reserveExposure", defaultValue = "false") boolean reserveExposure
    ) {
        SessionInfo session = requireSession(authHeader);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessCreditRisk(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "file is required"));
        }
        try {
            byte[] bytes = orderExcelService.evaluateWorkbook(
                    file.getInputStream(),
                    session.displayName(),
                    reserveExposure
            );
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"order-credit-decisions.xlsx\"")
                    .contentType(MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(bytes);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", ex.getMessage() != null ? ex.getMessage() : "Excel evaluate failed"));
        }
    }

    @PostMapping("/orders/override")
    public ResponseEntity<?> overrideOrder(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody OverrideRequest request
    ) {
        SessionInfo session = requireSession(authHeader);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canOverrideCreditRiskDecision(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (request == null || request.auditId() == null || request.newDecision() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "auditId and newDecision are required"));
        }
        try {
            CreditDecisionAuditDocument doc = creditRiskService.overrideDecision(
                    request.auditId(),
                    request.newDecision(),
                    request.reason(),
                    session.displayName()
            );
            return ResponseEntity.ok(doc);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @GetMapping("/config")
    public ResponseEntity<?> getConfig(
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        SessionInfo session = requireSession(authHeader);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessCreditRisk(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(creditRiskService.getConfig());
    }

    @PutMapping("/config")
    public ResponseEntity<?> updateConfig(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody CreditRiskEngineConfigDocument body
    ) {
        SessionInfo session = requireSession(authHeader);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canEditCreditRiskConfig(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(creditRiskService.updateConfig(body, session.displayName()));
    }

    @GetMapping("/audit")
    public ResponseEntity<?> audit(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "customerKey", required = false) String customerKey,
            @RequestParam(value = "limit", defaultValue = "50") int limit
    ) {
        SessionInfo session = requireSession(authHeader);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessCreditRisk(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        return ResponseEntity.ok(creditRiskService.audit(customerKey, safeLimit));
    }

    @PostMapping("/customers/manual-hold")
    public ResponseEntity<?> manualHold(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody ManualHoldRequest request
    ) {
        SessionInfo session = requireSession(authHeader);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canSetCreditRiskManualHold(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (request == null || request.customerKey() == null || request.customerKey().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "customerKey is required"));
        }
        try {
            PaymentDateOverride updated = creditRiskService.setManualHold(
                    request.customerKey().trim(),
                    Boolean.TRUE.equals(request.hold()),
                    request.reason(),
                    session.displayName()
            );
            return ResponseEntity.ok(Map.of(
                    "customerKey", updated.customerKey(),
                    "manualHold", updated.isManualHold(),
                    "manualHoldReason", updated.manualHoldReason() != null ? updated.manualHoldReason() : ""
            ));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/customers/apply-recommended-limit")
    public ResponseEntity<?> applyRecommendedLimit(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody CustomerLookupRequest request
    ) {
        SessionInfo session = requireSession(authHeader);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canEditCustomerLimit(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Requires customerLimitEdit permission"));
        }
        String q = resolveQuery(request);
        if (q == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "customer or customerKey is required"));
        }
        try {
            return ResponseEntity.ok(creditRiskService.applyRecommendedLimit(q, session.displayName()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/customers/payment-terms")
    public ResponseEntity<?> setPaymentTerms(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody PaymentTermsRequest request
    ) {
        SessionInfo session = requireSession(authHeader);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessCreditRisk(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (request == null || request.customerKey() == null || request.customerKey().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "customerKey is required"));
        }
        try {
            PaymentDateOverride updated = creditRiskService.setPaymentTermsDays(
                    request.customerKey().trim(),
                    request.paymentTermsDays(),
                    session.displayName()
            );
            return ResponseEntity.ok(Map.of(
                    "customerKey", updated.customerKey(),
                    "paymentTermsDays", updated.paymentTermsDays() != null ? updated.paymentTermsDays() : 0
            ));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping(value = "/payments/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadPayments(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam("file") MultipartFile file
    ) {
        SessionInfo session = requireSession(authHeader);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessCreditRisk(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "file is required"));
        }
        try {
            CreditPaymentEventUpload saved = creditRiskService.uploadPaymentEvents(file, session.displayName());
            return ResponseEntity.ok(Map.of(
                    "id", saved.id(),
                    "uploadedAt", saved.uploadedAt().toString(),
                    "filename", saved.file() != null ? saved.file().originalFilename() : "",
                    "format", saved.format() != null ? saved.format() : CustomerLedgerExcelUploadValidation.FORMAT_CUSTOMER_LEDGER,
                    "dataRowCount", saved.dataRowCount() != null ? saved.dataRowCount() : 0
            ));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", ex.getMessage() != null ? ex.getMessage() : "Upload failed"));
        }
    }

    @GetMapping("/promises")
    public ResponseEntity<?> listPromises(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam("customerKey") String customerKey
    ) {
        SessionInfo session = requireSession(authHeader);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessCreditRisk(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (customerKey == null || customerKey.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "customerKey is required"));
        }
        return ResponseEntity.ok(creditRiskService.listPromises(customerKey.trim()));
    }

    @PostMapping("/promises")
    public ResponseEntity<?> createPromise(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody PromiseCreateRequest request
    ) {
        SessionInfo session = requireSession(authHeader);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessCreditRisk(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (request == null || request.customerKey() == null || request.customerKey().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "customerKey is required"));
        }
        boolean hasDate = request.promiseDate() != null && !request.promiseDate().isBlank();
        boolean hasDays = request.daysUntil() != null;
        try {
            return ResponseEntity.ok(creditRiskService.createPromise(
                    request.customerKey().trim(),
                    request.customerName(),
                    request.voucherNo(),
                    hasDate ? request.promiseDate().trim() : null,
                    hasDays ? request.daysUntil() : null,
                    request.promiseAmount(),
                    request.note(),
                    session.displayName()
            ));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/promises/status")
    public ResponseEntity<?> updatePromiseStatus(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody PromiseStatusRequest request
    ) {
        SessionInfo session = requireSession(authHeader);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessCreditRisk(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (request == null || request.id() == null || request.id().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "id is required"));
        }
        try {
            return ResponseEntity.ok(creditRiskService.updatePromise(
                    request.id(),
                    Boolean.TRUE.equals(request.fulfilled()),
                    Boolean.TRUE.equals(request.broken()),
                    session.displayName()
            ));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/promises/delete")
    public ResponseEntity<?> deletePromise(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody PromiseDeleteRequest request
    ) {
        SessionInfo session = requireSession(authHeader);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessCreditRisk(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (request == null || request.id() == null || request.id().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "id is required"));
        }
        creditRiskService.deletePromise(request.id());
        return ResponseEntity.ok(Map.of("deleted", true));
    }

    private SessionInfo requireSession(String authHeader) {
        return authSessionService.validate(extractToken(authHeader));
    }

    private static String extractToken(String authHeader) {
        if (authHeader == null || authHeader.isBlank()) {
            return null;
        }
        String t = authHeader.trim();
        if (t.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return t.substring(7).trim();
        }
        return t;
    }

    private static String resolveQuery(CustomerLookupRequest request) {
        if (request == null) {
            return null;
        }
        if (request.phone() != null && !request.phone().isBlank()) {
            return request.phone().trim();
        }
        if (request.customer() != null && !request.customer().isBlank()) {
            return request.customer().trim();
        }
        if (request.customerKey() != null && !request.customerKey().isBlank()) {
            return request.customerKey().trim();
        }
        return null;
    }

    public record CustomerLookupRequest(String customer, String phone, String customerKey) {
    }

    public record EvaluateOrderRequest(
            String customer,
            String customerKey,
            Double orderAmount,
            String referenceId,
            Boolean reserveExposure
    ) {
    }

    public record OverrideRequest(String auditId, String newDecision, String reason) {
    }

    public record ManualHoldRequest(String customerKey, Boolean hold, String reason) {
    }

    public record PaymentTermsRequest(String customerKey, Integer paymentTermsDays) {
    }

    public record PromiseCreateRequest(
            String customerKey,
            String customerName,
            String voucherNo,
            String promiseDate,
            Integer daysUntil,
            Double promiseAmount,
            String note
    ) {
    }

    public record PromiseStatusRequest(String id, Boolean fulfilled, Boolean broken) {
    }

    public record PromiseDeleteRequest(String id) {
    }
}
