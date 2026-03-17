package org.example.api;

import org.example.auth.AuthSessionService;
import org.example.auth.SessionInfo;
import org.example.security.SecurityAuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.upload.DetailedSalesInvoicesUpload;
import org.example.upload.DetailedSalesInvoicesUploadRepository;
import org.example.upload.ReceivableAgeingReportUpload;
import org.example.upload.ReceivableAgeingReportUploadRepository;
import org.example.upload.UploadAuditEntry;
import org.example.upload.UploadAuditEntryRepository;
import org.example.upload.UploadResponse;
import org.example.upload.UploadStorageService;
import org.example.upload.UploadedExcelFileDownloadResponse;
import org.example.upload.UploadedExcelFileEntryResponse;
import org.example.upload.UploadPurgeResponse;
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
import org.springframework.dao.DataAccessException;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api")
public class UploadController {
    private static final Logger logger = LoggerFactory.getLogger(UploadController.class);
    private final AuthSessionService authSessionService;
    private final UploadStorageService uploadStorageService;
    private final DetailedSalesInvoicesUploadRepository detailedSalesInvoicesUploadRepository;
    private final ReceivableAgeingReportUploadRepository receivableAgeingReportUploadRepository;
    private final UploadAuditEntryRepository uploadAuditEntryRepository;
    private final ObjectMapper objectMapper;
    private final SecurityAuditService securityAuditService;
    
    // Allowed file extensions for uploads
    private static final List<String> ALLOWED_EXTENSIONS = Arrays.asList(".xlsx", ".xls");
    private static final long MAX_FILE_SIZE = 500 * 1024 * 1024; // 500MB
    private static final List<String> ALLOWED_CONTENT_TYPES = Arrays.asList(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-excel"
    );

    public UploadController(AuthSessionService authSessionService,
                            UploadStorageService uploadStorageService,
                            DetailedSalesInvoicesUploadRepository detailedSalesInvoicesUploadRepository,
                            ReceivableAgeingReportUploadRepository receivableAgeingReportUploadRepository,
                            UploadAuditEntryRepository uploadAuditEntryRepository,
                            ObjectMapper objectMapper,
                            SecurityAuditService securityAuditService) {
        this.authSessionService = authSessionService;
        this.uploadStorageService = uploadStorageService;
        this.detailedSalesInvoicesUploadRepository = detailedSalesInvoicesUploadRepository;
        this.receivableAgeingReportUploadRepository = receivableAgeingReportUploadRepository;
        this.uploadAuditEntryRepository = uploadAuditEntryRepository;
        this.objectMapper = objectMapper;
        this.securityAuditService = securityAuditService;
    }

    @PostMapping("/upload")
    public ResponseEntity<UploadResponse> upload(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam("file1") MultipartFile file1,
            @RequestParam("file2") MultipartFile file2
    ) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        if (file1.isEmpty() || file2.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(new UploadResponse("failed", "Both files are required.", List.of()));
        }

        // SECURITY: Validate file uploads
        String validationError = validateFileUpload(file1, "file1");
        if (validationError != null) {
            securityAuditService.logFileUpload(session.userId(), file1.getOriginalFilename(), file1.getSize(), false);
            return ResponseEntity.badRequest()
                    .body(new UploadResponse("failed", "File 1: " + validationError, List.of()));
        }
        
        validationError = validateFileUpload(file2, "file2");
        if (validationError != null) {
            securityAuditService.logFileUpload(session.userId(), file2.getOriginalFilename(), file2.getSize(), false);
            return ResponseEntity.badRequest()
                    .body(new UploadResponse("failed", "File 2: " + validationError, List.of()));
        }

        try {
            logger.info("Upload request received. file1={}, file2={}", file1.getOriginalFilename(), file2.getOriginalFilename());
            
            List<org.example.upload.UploadFileInfo> storedFiles = uploadStorageService.storeFiles(file1, file2);
            
            // Log successful upload
            securityAuditService.logFileUpload(session.userId(), file1.getOriginalFilename(), file1.getSize(), true);
            securityAuditService.logFileUpload(session.userId(), file2.getOriginalFilename(), file2.getSize(), true);
            
            return ResponseEntity.ok(new UploadResponse(
                    "success",
                    "Files uploaded successfully.",
                    storedFiles
            ));
        } catch (IllegalArgumentException ex) {
            logger.warn("Upload rejected. {}", ex.getMessage());
            return ResponseEntity.badRequest()
                    .body(new UploadResponse("failed", ex.getMessage(), List.of()));
        } catch (org.bson.BsonMaximumSizeExceededException ex) {
            logger.error("Upload failed: File data exceeds MongoDB document size limit (16MB).", ex);
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(new UploadResponse("failed", "File is too large. The Excel file contains too much data (exceeds MongoDB's 16MB document limit). Please reduce the number of rows or split the data into smaller files.", List.of()));
        } catch (DataAccessException ex) {
            logger.error("Upload failed due to database error.", ex);
            String errorMessage = "Upload failed due to database error.";
            Throwable cause = ex.getCause();
            if (cause != null) {
                String causeMessage = cause.getMessage();
                if (cause instanceof org.bson.BsonMaximumSizeExceededException || 
                    (causeMessage != null && (causeMessage.contains("16777216") || causeMessage.contains("BsonMaximumSizeExceededException")))) {
                    errorMessage = "File is too large. The Excel file contains too much data (exceeds MongoDB's 16MB document limit). Please reduce the number of rows or split the data into smaller files.";
                }
            }
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(new UploadResponse("failed", errorMessage, List.of()));
        } catch (IOException ex) {
            logger.error("Upload failed while processing files.", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new UploadResponse("failed", "Upload failed. Please check the file format and try again.", List.of()));
        } catch (Exception ex) {
            logger.error("Upload failed with unexpected error.", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new UploadResponse("failed", "Upload failed. Please try again or contact support if the problem persists.", List.of()));
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

        return ResponseEntity.ok(uploadAuditEntryRepository.findAllByOrderByUploadedAtDesc());
    }

    @PostMapping("/uploads/purge")
    public ResponseEntity<UploadPurgeResponse> purgeUploads(
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        
        // SECURITY: Only admin users can purge all uploaded data (destructive operation)
        if (!session.isAdmin()) {
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
    
    /**
     * Validate file upload for security.
     * Checks file extension, size, and content type.
     */
    private String validateFileUpload(MultipartFile file, String fileLabel) {
        if (file == null || file.isEmpty()) {
            return "File is empty";
        }
        
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            return "Filename is invalid";
        }
        
        // SECURITY: Check for path traversal attempts
        if (originalFilename.contains("..") || originalFilename.contains("/") || originalFilename.contains("\\")) {
            return "Filename contains invalid characters";
        }
        
        // Check file extension
        String lowerFilename = originalFilename.toLowerCase();
        boolean hasValidExtension = ALLOWED_EXTENSIONS.stream()
                .anyMatch(lowerFilename::endsWith);
        
        if (!hasValidExtension) {
            return "Invalid file type. Only Excel files (.xlsx, .xls) are allowed";
        }
        
        // Check file size
        if (file.getSize() > MAX_FILE_SIZE) {
            return String.format("File size exceeds maximum allowed size of %d MB", MAX_FILE_SIZE / (1024 * 1024));
        }
        
        // Check content type (if provided)
        String contentType = file.getContentType();
        if (contentType != null && !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase())) {
            // Log suspicious activity but don't block (content type can be spoofed)
            logger.warn("Upload with unexpected content type: {} for file: {}", contentType, originalFilename);
        }
        
        return null; // Validation passed
    }
}

