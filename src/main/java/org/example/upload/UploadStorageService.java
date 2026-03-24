package org.example.upload;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Service
public class UploadStorageService {
    private static final Logger logger = LoggerFactory.getLogger(UploadStorageService.class);

    /** Cooperative cancel checks during row iteration (large sheets). */
    private static final int ROWS_PER_CANCEL_CHECK = 512;

    /** Oldest audit rows are removed so the collection stays small (storage cost). */
    public static final int MAX_UPLOAD_AUDIT_ENTRIES = 100;

    /** Delete oldest rows in chunks to avoid loading huge result sets into the app. */
    private static final int AUDIT_DELETE_BATCH_SIZE = 500;

    private static final Sort AUDIT_OLDEST_FIRST =
            Sort.by(Sort.Direction.ASC, "uploadedAt").and(Sort.by(Sort.Direction.ASC, "id"));
    
    private final DetailedSalesInvoicesUploadRepository detailedSalesInvoicesUploadRepository;
    private final ReceivableAgeingReportUploadRepository receivableAgeingReportUploadRepository;
    private final UploadAuditEntryRepository uploadAuditEntryRepository;

    public UploadStorageService(DetailedSalesInvoicesUploadRepository detailedSalesInvoicesUploadRepository,
                                ReceivableAgeingReportUploadRepository receivableAgeingReportUploadRepository,
                                UploadAuditEntryRepository uploadAuditEntryRepository) {
        this.detailedSalesInvoicesUploadRepository = detailedSalesInvoicesUploadRepository;
        this.receivableAgeingReportUploadRepository = receivableAgeingReportUploadRepository;
        this.uploadAuditEntryRepository = uploadAuditEntryRepository;
    }

    public List<UploadFileInfo> storeFiles(MultipartFile file1, MultipartFile file2) throws IOException {
        validateExcelFile(file1);
        validateExcelFile(file2);
        return storeFiles(
                file1.getInputStream(), file1.getOriginalFilename(),
                file2.getInputStream(), file2.getOriginalFilename(),
                UploadCancelChecker.NONE,
                null
        );
    }

    /**
     * Same as {@link #storeFiles(MultipartFile, MultipartFile)} but reads from temp paths (e.g. async upload after multipart is saved to disk).
     */
    public List<UploadFileInfo> storeFiles(Path tempFile1, Path tempFile2, String originalFilename1, String originalFilename2)
            throws IOException {
        return storeFiles(tempFile1, tempFile2, originalFilename1, originalFilename2, UploadCancelChecker.NONE, null);
    }

    /**
     * Async path: {@code checker} runs during parse; {@code onSavingPhaseStarted} runs after successful parse, after a final cancel check,
     * and immediately before DB deletes (cancellation is not honored once this runs).
     */
    public List<UploadFileInfo> storeFiles(
            Path tempFile1,
            Path tempFile2,
            String originalFilename1,
            String originalFilename2,
            UploadCancelChecker cancelChecker,
            Runnable onSavingPhaseStarted
    ) throws IOException {
        validateExcelFilename(originalFilename1);
        validateExcelFilename(originalFilename2);
        try (InputStream is1 = Files.newInputStream(tempFile1);
             InputStream is2 = Files.newInputStream(tempFile2)) {
            return storeFiles(is1, originalFilename1, is2, originalFilename2, cancelChecker, onSavingPhaseStarted);
        }
    }

    private List<UploadFileInfo> storeFiles(
            InputStream inputStream1,
            String originalFilename1,
            InputStream inputStream2,
            String originalFilename2,
            UploadCancelChecker cancelChecker,
            Runnable onSavingPhaseStarted
    ) throws IOException {
        Instant uploadedAt = Instant.now();
        UploadedExcelFile detailedFile = parseExcel(inputStream1, originalFilename1, cancelChecker);
        UploadedExcelFile receivableFile = parseExcel(inputStream2, originalFilename2, cancelChecker);

        cancelChecker.checkCancelled();
        if (onSavingPhaseStarted != null) {
            onSavingPhaseStarted.run();
        }

        logger.info("Clearing previous upload data before new upload.");
        recordDeletionAudit();
        detailedSalesInvoicesUploadRepository.deleteAll();
        receivableAgeingReportUploadRepository.deleteAll();

        List<UploadFileInfo> fileInfos = new ArrayList<>();

        // Store detailed file
        logger.info("Saving DetailedSalesInvoices upload: {}", detailedFile.originalFilename());
        DetailedSalesInvoicesUpload detailedDoc = detailedSalesInvoicesUploadRepository.save(
                new DetailedSalesInvoicesUpload(null, uploadedAt, detailedFile)
        );
        fileInfos.add(new UploadFileInfo(detailedDoc.id(), detailedFile.originalFilename()));

        // Store receivable file
        logger.info("Saving ReceivableAgeingReport upload: {}", receivableFile.originalFilename());
        ReceivableAgeingReportUpload receivableDoc = receivableAgeingReportUploadRepository.save(
                new ReceivableAgeingReportUpload(null, uploadedAt, receivableFile)
        );
        fileInfos.add(new UploadFileInfo(receivableDoc.id(), receivableFile.originalFilename()));

        uploadAuditEntryRepository.save(
                new UploadAuditEntry(null, "ADDED", "detailed", detailedFile.originalFilename(), uploadedAt)
        );
        uploadAuditEntryRepository.save(
                new UploadAuditEntry(null, "ADDED", "receivable", receivableFile.originalFilename(), uploadedAt)
        );
        enforceUploadAuditRetention();

        logger.info("Upload completed.");
        return fileInfos;
    }


    /**
     * Keeps the most recent {@value #MAX_UPLOAD_AUDIT_ENTRIES} audit entries (by {@code uploadedAt}, then {@code id});
     * deletes older rows using bounded queries (no full collection load).
     */
    public void enforceUploadAuditRetention() {
        long total = uploadAuditEntryRepository.count();
        if (total <= MAX_UPLOAD_AUDIT_ENTRIES) {
            return;
        }
        logger.info("Trimming upload_audit_entries from {} down to at most {} entries.", total, MAX_UPLOAD_AUDIT_ENTRIES);
        while (true) {
            total = uploadAuditEntryRepository.count();
            if (total <= MAX_UPLOAD_AUDIT_ENTRIES) {
                return;
            }
            int toRemove = (int) Math.min(total - MAX_UPLOAD_AUDIT_ENTRIES, (long) AUDIT_DELETE_BATCH_SIZE);
            Page<UploadAuditEntry> page = uploadAuditEntryRepository.findAll(PageRequest.of(0, toRemove, AUDIT_OLDEST_FIRST));
            List<UploadAuditEntry> oldest = page.getContent();
            if (oldest.isEmpty()) {
                return;
            }
            uploadAuditEntryRepository.deleteAll(oldest);
        }
    }

    private void recordDeletionAudit() {
        Instant now = Instant.now();
        List<DetailedSalesInvoicesUpload> detailedUploads = detailedSalesInvoicesUploadRepository.findAll();
        for (DetailedSalesInvoicesUpload upload : detailedUploads) {
            uploadAuditEntryRepository.save(
                    new UploadAuditEntry(null, "DELETED", "detailed", upload.file().originalFilename(), now)
            );
        }
        List<ReceivableAgeingReportUpload> receivableUploads = receivableAgeingReportUploadRepository.findAll();
        for (ReceivableAgeingReportUpload upload : receivableUploads) {
            uploadAuditEntryRepository.save(
                    new UploadAuditEntry(null, "DELETED", "receivable", upload.file().originalFilename(), now)
            );
        }
    }

    private UploadedExcelFile parseExcel(InputStream inputStream, String originalFilename, UploadCancelChecker checker)
            throws IOException {
        checker.checkCancelled();
        try (InputStream in = inputStream;
             Workbook workbook = WorkbookFactory.create(in)) {
            List<UploadedExcelSheet> sheets = new ArrayList<>();
            DataFormatter formatter = new DataFormatter();

            for (Sheet sheet : workbook) {
                checker.checkCancelled();
                sheets.add(parseSheet(sheet, formatter, checker));
            }

            return new UploadedExcelFile(safeName(originalFilename), sheets);
        } catch (IOException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof InvalidFormatException) {
                throw new IllegalArgumentException("Invalid .xlsx file. Please re-save as Excel (.xlsx).", ex);
            }
            throw ex;
        }
    }

    private void validateExcelFile(MultipartFile file) {
        validateExcelFilename(file.getOriginalFilename());
    }

    private void validateExcelFilename(String originalFilename) {
        String name = originalFilename == null ? "" : originalFilename.toLowerCase();
        if (!name.endsWith(".xlsx")) {
            throw new IllegalArgumentException("Invalid file type. Please upload .xlsx files only.");
        }
    }

    private UploadedExcelSheet parseSheet(Sheet sheet, DataFormatter formatter, UploadCancelChecker checker) {
        int headerRowIndex = findHeaderRow(sheet, formatter, checker);
        Row headerRow = sheet.getRow(headerRowIndex);
        if (headerRow == null) {
            return new UploadedExcelSheet(sheet.getSheetName(), List.of(), List.of());
        }

        int lastCell = Math.max(headerRow.getLastCellNum(), 0);
        List<String> headers = buildHeaders(headerRow, lastCell, formatter);
        List<Map<String, String>> rows = new ArrayList<>();

        int dataRowCounter = 0;
        for (int rowIndex = headerRowIndex + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            if (++dataRowCounter % ROWS_PER_CANCEL_CHECK == 0) {
                checker.checkCancelled();
            }
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }
            Map<String, String> rowData = new LinkedHashMap<>();
            boolean hasValue = false;

            for (int cellIndex = 0; cellIndex < headers.size(); cellIndex++) {
                Cell cell = row.getCell(cellIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                String value = cell == null ? "" : formatter.formatCellValue(cell).trim();
                if (!value.isBlank()) {
                    hasValue = true;
                }
                rowData.put(headers.get(cellIndex), value);
            }

            if (hasValue) {
                rows.add(rowData);
            }
        }

        return new UploadedExcelSheet(sheet.getSheetName(), headers, rows);
    }

    private int findHeaderRow(Sheet sheet, DataFormatter formatter, UploadCancelChecker checker) {
        int firstRow = sheet.getFirstRowNum();
        int lastRow = sheet.getLastRowNum();
        int scanned = 0;
        for (int rowIndex = firstRow; rowIndex <= lastRow; rowIndex++) {
            if (++scanned % 32 == 0) {
                checker.checkCancelled();
            }
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }
            int lastCell = Math.max(row.getLastCellNum(), 0);
            for (int cellIndex = 0; cellIndex < lastCell; cellIndex++) {
                Cell cell = row.getCell(cellIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                if (cell == null) {
                    continue;
                }
                String value = formatter.formatCellValue(cell).trim().toLowerCase();
                if (value.contains("customer")) {
                    return rowIndex;
                }
            }
        }
        return firstRow;
    }

    private List<String> buildHeaders(Row headerRow, int lastCell, DataFormatter formatter) {
        List<String> headers = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();

        for (int cellIndex = 0; cellIndex < lastCell; cellIndex++) {
            Cell cell = headerRow.getCell(cellIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            String header = cell == null ? "" : formatter.formatCellValue(cell).trim();
            if (header.isBlank()) {
                header = "Column " + (cellIndex + 1);
            }

            int count = seen.getOrDefault(header, 0);
            seen.put(header, count + 1);
            if (count > 0) {
                header = header + " (" + (count + 1) + ")";
            }
            headers.add(header);
        }

        return headers;
    }

    private String safeName(String originalName) {
        return originalName == null || originalName.isBlank() ? "file" : originalName;
    }
}

