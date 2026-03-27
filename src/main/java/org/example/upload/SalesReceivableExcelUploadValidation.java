package org.example.upload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Locale;

/**
 * Single source of truth for sales/receivable Excel upload constraints before parsing.
 * Sheet/column expectations live in {@link ExcelUploadHeaderRules}.
 */
public final class SalesReceivableExcelUploadValidation {

    private static final Logger log = LoggerFactory.getLogger(SalesReceivableExcelUploadValidation.class);

    public static final String ALLOWED_EXTENSION = ".xlsx";
    public static final long MAX_FILE_SIZE_BYTES = 500L * 1024 * 1024;

    public static final List<String> ALLOWED_CONTENT_TYPES = List.of(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-excel"
    );

    private SalesReceivableExcelUploadValidation() {
    }

    /**
     * @return error message, or {@code null} if the multipart passes all checks
     */
    public static String validateMultipart(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return "File is empty";
        }
        String nameErr = validateOriginalFilenameMessage(file.getOriginalFilename());
        if (nameErr != null) {
            return nameErr;
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            return String.format(
                    "File size exceeds maximum allowed size of %d MB",
                    MAX_FILE_SIZE_BYTES / (1024 * 1024)
            );
        }
        String contentType = file.getContentType();
        if (contentType != null) {
            String ct = contentType.toLowerCase(Locale.ROOT);
            if (!ALLOWED_CONTENT_TYPES.contains(ct)) {
                log.warn("Upload with unexpected content type: {} for file: {}", contentType, file.getOriginalFilename());
            }
        }
        return null;
    }

    public static void validateMultipartOrThrow(MultipartFile file) {
        String err = validateMultipart(file);
        if (err != null) {
            throw new IllegalArgumentException(err);
        }
    }

    /**
     * Filename-only checks (async path after temp files are written; defense in depth).
     */
    public static void validateOriginalFilenameOrThrow(String originalFilename) {
        String err = validateOriginalFilenameMessage(originalFilename);
        if (err != null) {
            throw new IllegalArgumentException(err);
        }
    }

    /**
     * @return error message, or {@code null} if valid
     */
    public static String validateOriginalFilenameMessage(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "Filename is invalid";
        }
        if (originalFilename.contains("..") || originalFilename.contains("/") || originalFilename.contains("\\")) {
            return "Filename contains invalid characters";
        }
        String lower = originalFilename.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(ALLOWED_EXTENSION)) {
            return "Invalid file type. Only Excel .xlsx files are allowed";
        }
        return null;
    }
}
