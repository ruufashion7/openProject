package org.example.api;

import org.example.auth.AuthSessionService;
import org.example.auth.SessionInfo;
import org.example.auth.SessionPermissions;
import org.example.security.SecurityAuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.upload.DetailedSalesInvoicesUpload;
import org.example.upload.DetailedSalesInvoicesUploadRepository;
import org.example.upload.ReceivableAgeingReportUpload;
import org.example.upload.ReceivableAgeingReportUploadRepository;
import org.example.upload.UploadAuditEntry;
import org.example.upload.UploadAuditEntryRepository;
import org.example.upload.UploadResponse;
import org.example.upload.UploadAsyncStateResponse;
import org.example.upload.UploadCancelResponse;
import org.example.upload.UploadConflictResponse;
import org.example.upload.UploadJobAcceptedResponse;
import org.example.upload.UploadJobPollResult;
import org.example.upload.UploadJobService;
import org.example.upload.UploadJobStatusResponse;
import org.example.upload.UploadStorageService;
import org.example.upload.UploadedExcelFileDownloadResponse;
import org.example.upload.UploadedExcelFileEntryResponse;
import org.example.upload.UploadPurgeResponse;
import org.example.upload.SalesReceivableExcelUploadValidation;
import org.example.upload.UploadStatusResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api")
public class UploadController {
    private static final Logger logger = LoggerFactory.getLogger(UploadController.class);
    private final AuthSessionService authSessionService;
    private final UploadStorageService uploadStorageService;
    private final UploadJobService uploadJobService;
    private final DetailedSalesInvoicesUploadRepository detailedSalesInvoicesUploadRepository;
    private final ReceivableAgeingReportUploadRepository receivableAgeingReportUploadRepository;
    private final UploadAuditEntryRepository uploadAuditEntryRepository;
    private final ObjectMapper objectMapper;
    private final SecurityAuditService securityAuditService;

    public UploadController(AuthSessionService authSessionService,
                            UploadStorageService uploadStorageService,
                            UploadJobService uploadJobService,
                            DetailedSalesInvoicesUploadRepository detailedSalesInvoicesUploadRepository,
                            ReceivableAgeingReportUploadRepository receivableAgeingReportUploadRepository,
                            UploadAuditEntryRepository uploadAuditEntryRepository,
                            ObjectMapper objectMapper,
                            SecurityAuditService securityAuditService) {
        this.authSessionService = authSessionService;
        this.uploadStorageService = uploadStorageService;
        this.uploadJobService = uploadJobService;
        this.detailedSalesInvoicesUploadRepository = detailedSalesInvoicesUploadRepository;
        this.receivableAgeingReportUploadRepository = receivableAgeingReportUploadRepository;
        this.uploadAuditEntryRepository = uploadAuditEntryRepository;
        this.objectMapper = objectMapper;
        this.securityAuditService = securityAuditService;
    }

    @PostMapping("/upload")
    public ResponseEntity<?> upload(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam("file1") MultipartFile file1,
            @RequestParam("file2") MultipartFile file2
    ) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessFileUpload(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new UploadResponse("failed", "You do not have permission to upload files.", List.of()));
        }

        String validationError = SalesReceivableExcelUploadValidation.validateMultipart(file1);
        if (validationError != null) {
            securityAuditService.logFileUpload(session.userId(), file1.getOriginalFilename(), file1.getSize(), false);
            return ResponseEntity.badRequest()
                    .body(new UploadResponse("failed", "File 1: " + validationError, List.of()));
        }

        validationError = SalesReceivableExcelUploadValidation.validateMultipart(file2);
        if (validationError != null) {
            securityAuditService.logFileUpload(session.userId(), file2.getOriginalFilename(), file2.getSize(), false);
            return ResponseEntity.badRequest()
                    .body(new UploadResponse("failed", "File 2: " + validationError, List.of()));
        }

        logger.info("Upload request received. file1={}, file2={}", file1.getOriginalFilename(), file2.getOriginalFilename());

        Path temp1 = null;
        Path temp2 = null;
        try {
            temp1 = Files.createTempFile("upload-detailed-", ".xlsx");
            temp2 = Files.createTempFile("upload-receivable-", ".xlsx");
            file1.transferTo(temp1);
            file2.transferTo(temp2);
        } catch (IOException ex) {
            logger.error("Failed to store multipart files to temp.", ex);
            deleteTempQuietly(temp1);
            deleteTempQuietly(temp2);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new UploadResponse("failed", "Could not read upload. Please try again.", List.of()));
        }

        UploadJobService.StartJobOutcome outcome = uploadJobService.startJob(
                session.userId(),
                session.displayName(),
                temp1,
                temp2,
                file1.getOriginalFilename(),
                file2.getOriginalFilename(),
                file1.getSize(),
                file2.getSize()
        );

        if (outcome.jobId().isEmpty()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new UploadConflictResponse(
                            "failed",
                            "An upload is already in progress. Wait until it finishes, cancel it from the upload page, or poll GET /api/upload/state.",
                            outcome.blockedByJobId().orElse(null)
                    ));
        }

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new UploadJobAcceptedResponse(
                        outcome.jobId().get(),
                        "Upload queued. Poll GET /api/upload/jobs/{jobId} or GET /api/upload/state for progress."
                ));
    }

    @GetMapping("/upload/state")
    public ResponseEntity<UploadAsyncStateResponse> uploadAsyncState(
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessFileUpload(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(uploadJobService.getAsyncState());
    }

    @PostMapping("/upload/jobs/{jobId}/cancel")
    public ResponseEntity<?> cancelUploadJob(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable String jobId
    ) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessFileUpload(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (jobId == null || jobId.isBlank() || jobId.length() > 128) {
            return ResponseEntity.badRequest().build();
        }

        UploadJobService.CancelRequestResult result = uploadJobService.requestCancel(jobId, session.userId());
        if (result instanceof UploadJobService.CancelRequestResult.Accepted) {
            return ResponseEntity.ok(new UploadCancelResponse(
                    "accepted",
                    "Stop requested. If the upload is still reading Excel, it will stop before changing database data."
            ));
        }
        if (result instanceof UploadJobService.CancelRequestResult.NotFound) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        if (result instanceof UploadJobService.CancelRequestResult.NotActive) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new UploadCancelResponse("failed", "That upload is not running anymore (already finished, failed, or cancelled)."));
        }
        if (result instanceof UploadJobService.CancelRequestResult.CannotCancelWhileSaving) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new UploadCancelResponse(
                            "failed",
                            "Cannot cancel while saving to the database. Wait a few seconds for this step to finish."
                    ));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    @GetMapping("/upload/jobs/{jobId}")
    public ResponseEntity<UploadJobStatusResponse> uploadJobStatus(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable String jobId
    ) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessFileUpload(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        if (jobId == null || jobId.isBlank() || jobId.length() > 128) {
            return ResponseEntity.badRequest().build();
        }

        UploadJobPollResult poll = uploadJobService.pollJob(jobId);
        return switch (poll.type()) {
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case OK -> ResponseEntity.ok(poll.body());
        };
    }

    private static void deleteTempQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            LoggerFactory.getLogger(UploadController.class).warn("Could not delete temp file {}", path, ex);
        }
    }

    @GetMapping("/uploads")
    public ResponseEntity<List<UploadedExcelFileEntryResponse>> listUploads(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "type", required = false) String type
    ) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessFileUpload(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        return ResponseEntity.ok(resolveCurrent(type));
    }

    @GetMapping("/uploads/status")
    public ResponseEntity<UploadStatusResponse> uploadStatus(
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        boolean hasDetailed = detailedSalesInvoicesUploadRepository.findTopByOrderByUploadedAtDesc() != null;
        boolean hasReceivable = receivableAgeingReportUploadRepository.findTopByOrderByUploadedAtDesc() != null;
        boolean ready = hasDetailed && hasReceivable;
        return ResponseEntity.ok(new UploadStatusResponse(hasDetailed, hasReceivable, ready));
    }

    @GetMapping("/uploads/{type}/{id}/json")
    public ResponseEntity<byte[]> downloadUploadJson(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable String type,
            @PathVariable String id
    ) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessFileUpload(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // SECURITY: Validate path variables to prevent path traversal
        if (type == null || (!type.equals("detailed") && !type.equals("receivable"))) {
            return ResponseEntity.badRequest().build();
        }

        if (id == null || id.isBlank() || id.length() > 100) {
            return ResponseEntity.badRequest().build();
        }

        try {
            Optional<UploadedExcelFileDownloadResponse> payload = resolveDownload(type, id);
            if (payload.isEmpty()) {
                logger.warn("Download requested for missing upload. type={}, id={}", type, id);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }
            byte[] body = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(payload.get());
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"upload-" + type + "-" + id + ".json\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body);
        } catch (Exception ex) {
            logger.error("Failed to generate download JSON. type={}, id={}", type, id, ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/uploads/latest/{type}/json")
    public ResponseEntity<byte[]> downloadLatestJson(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable String type
    ) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessFileUpload(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // SECURITY: Validate path variable
        if (type == null || (!type.equals("detailed") && !type.equals("receivable"))) {
            return ResponseEntity.badRequest().build();
        }

        Optional<UploadedExcelFileDownloadResponse> payload = resolveLatest(type);
        if (payload.isEmpty()) {
            logger.warn("Latest download requested but no data for type={}", type);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        try {
            byte[] body = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(payload.get());
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"upload-" + type + "-latest.json\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body);
        } catch (Exception ex) {
            logger.error("Failed to generate latest download JSON. type={}", type, ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/uploads/audit")
    public ResponseEntity<List<UploadAuditEntry>> listAudit(
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessFileUpload(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        return ResponseEntity.ok(uploadAuditEntryRepository.findTop100ByOrderByUploadedAtDescIdDesc());
    }

    @PostMapping("/uploads/purge")
    public ResponseEntity<UploadPurgeResponse> purgeUploads(
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // Destructive: admin or users with Hard Delete permission (matches Access Control UI)
        if (!session.isAdmin() && !SessionPermissions.canHardDelete(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        List<DetailedSalesInvoicesUpload> detailedUploads = detailedSalesInvoicesUploadRepository.findAll();
        List<ReceivableAgeingReportUpload> receivableUploads = receivableAgeingReportUploadRepository.findAll();
        long detailedCount = detailedUploads.size();
        long receivableCount = receivableUploads.size();
        Instant now = Instant.now();
        for (DetailedSalesInvoicesUpload upload : detailedUploads) {
            uploadAuditEntryRepository.save(
                    new UploadAuditEntry(null, "DELETED", "detailed", upload.file().originalFilename(), now)
            );
        }
        for (ReceivableAgeingReportUpload upload : receivableUploads) {
            uploadAuditEntryRepository.save(
                    new UploadAuditEntry(null, "DELETED", "receivable", upload.file().originalFilename(), now)
            );
        }
        uploadStorageService.enforceUploadAuditRetention();
        detailedSalesInvoicesUploadRepository.deleteAll();
        receivableAgeingReportUploadRepository.deleteAll();
        logger.info("Purged uploads. detailedDeleted={}, receivableDeleted={}", detailedCount, receivableCount);

        return ResponseEntity.ok(new UploadPurgeResponse(detailedCount, receivableCount));
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

    private Optional<UploadedExcelFileDownloadResponse> resolveDownload(String type, String id) {
        if ("detailed".equalsIgnoreCase(type)) {
            return detailedSalesInvoicesUploadRepository.findById(id)
                    .map(UploadedExcelFileDownloadResponse::from);
        }
        if ("receivable".equalsIgnoreCase(type)) {
            return receivableAgeingReportUploadRepository.findById(id)
                    .map(UploadedExcelFileDownloadResponse::from);
        }
        return Optional.empty();
    }

    private Optional<UploadedExcelFileDownloadResponse> resolveLatest(String type) {
        if ("detailed".equalsIgnoreCase(type)) {
            DetailedSalesInvoicesUpload latest = detailedSalesInvoicesUploadRepository.findTopByOrderByUploadedAtDesc();
            return latest == null ? Optional.empty() : Optional.of(UploadedExcelFileDownloadResponse.from(latest));
        }
        if ("receivable".equalsIgnoreCase(type)) {
            ReceivableAgeingReportUpload latest = receivableAgeingReportUploadRepository.findTopByOrderByUploadedAtDesc();
            return latest == null ? Optional.empty() : Optional.of(UploadedExcelFileDownloadResponse.from(latest));
        }
        return Optional.empty();
    }

    private List<UploadedExcelFileEntryResponse> resolveCurrent(String type) {
        List<UploadedExcelFileEntryResponse> entries = new java.util.ArrayList<>();
        if (type == null || type.isBlank() || "detailed".equalsIgnoreCase(type)) {
            DetailedSalesInvoicesUpload latest = detailedSalesInvoicesUploadRepository.findTopByOrderByUploadedAtDesc();
            if (latest != null) {
                entries.add(UploadedExcelFileEntryResponse.from(latest));
            }
        }
        if (type == null || type.isBlank() || "receivable".equalsIgnoreCase(type)) {
            ReceivableAgeingReportUpload latest = receivableAgeingReportUploadRepository.findTopByOrderByUploadedAtDesc();
            if (latest != null) {
                entries.add(UploadedExcelFileEntryResponse.from(latest));
            }
        }
        return entries;
    }
}

