package org.example.creditrisk;

import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.example.customer.CustomerIdentity;
import org.example.upload.ExcelUploadHeaderRules;
import org.example.upload.PoiSecurityLimits;
import org.example.upload.SalesReceivableExcelUploadValidation;
import org.example.upload.UploadedExcelFile;
import org.example.upload.UploadedExcelSheet;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CreditPaymentEventService {

    private final CreditPaymentEventUploadRepository repository;

    public CreditPaymentEventService(CreditPaymentEventUploadRepository repository) {
        this.repository = repository;
    }

    public CreditPaymentEventUpload store(MultipartFile file, String uploadedBy) throws IOException {
        SalesReceivableExcelUploadValidation.validateMultipartOrThrow(file);
        UploadedExcelFile parsed = parseCustomerLedgerExcel(file.getInputStream(), file.getOriginalFilename());
        return saveLedgerUpload(parsed, uploadedBy);
    }

    public UploadedExcelFile parseCustomerLedgerExcel(java.io.InputStream inputStream, String originalFilename)
            throws IOException {
        PoiSecurityLimits.apply();
        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            DataFormatter formatter = new DataFormatter();
            List<UploadedExcelSheet> sheets = new ArrayList<>();
            for (Sheet sheet : workbook) {
                sheets.add(parseCustomerLedgerSheet(sheet, formatter));
            }
            String name = originalFilename;
            return new UploadedExcelFile(name == null || name.isBlank() ? "CustomerLedger.xlsx" : name, sheets);
        } catch (IOException ex) {
            if (ex.getCause() instanceof InvalidFormatException) {
                throw new IllegalArgumentException("Invalid .xlsx file. Please re-save as Excel (.xlsx).", ex);
            }
            throw ex;
        }
    }

    public CreditPaymentEventUpload saveLedgerUpload(UploadedExcelFile parsed, String uploadedBy) {
        CustomerLedgerExcelUploadValidation.validateParsedOrThrow(parsed);
        int rowCount = CustomerLedgerExcelUploadValidation.countValidDataRows(parsed);
        CreditPaymentEventUpload previous = repository.findTopByOrderByUploadedAtDesc();
        CreditPaymentEventUpload saved = repository.save(new CreditPaymentEventUpload(
                null,
                Instant.now(),
                parsed,
                uploadedBy,
                CustomerLedgerExcelUploadValidation.FORMAT_CUSTOMER_LEDGER,
                rowCount
        ));
        if (previous != null && previous.id() != null && !previous.id().equals(saved.id())) {
            repository.deleteById(previous.id());
        }
        return saved;
    }

    public CreditPaymentEventUpload storeFromPath(java.nio.file.Path path, String originalFilename, String uploadedBy)
            throws IOException {
        SalesReceivableExcelUploadValidation.validateOriginalFilenameOrThrow(originalFilename);
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(path)) {
            UploadedExcelFile parsed = parseCustomerLedgerExcel(in, originalFilename);
            return saveLedgerUpload(parsed, uploadedBy);
        }
    }

    public CreditPaymentEventUpload latest() {
        return repository.findTopByOrderByUploadedAtDesc();
    }

    public boolean hasUpload() {
        return latest() != null;
    }

    /** Count fail/bounce/return transaction types in the last {@code windowDays} for a customer. */
    public int countFailures(String customerKey, String displayName, int windowDays) {
        CreditPaymentEventUpload latest = latest();
        if (latest == null || latest.file() == null) {
            return 0;
        }
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        int count = 0;
        for (UploadedExcelSheet sheet : latest.file().sheets()) {
            if (!ExcelUploadHeaderRules.isCustomerLedgerHeaderRow(sheet.headers())) {
                continue;
            }
            List<String> customerHeaders = sheet.headers().stream()
                    .filter(h -> h != null && h.trim().equalsIgnoreCase("Customer"))
                    .toList();
            List<String> txnHeaders = sheet.headers().stream()
                    .filter(ExcelUploadHeaderRules::isTransactionTypeHeader)
                    .toList();
            List<String> dateHeaders = sheet.headers().stream().filter(ExcelUploadHeaderRules::isPaymentDateHeader).toList();
            if (customerHeaders.isEmpty() || txnHeaders.isEmpty()) {
                continue;
            }
            for (Map<String, String> row : sheet.rows()) {
                String customer = CreditRiskParseSupport.firstNonBlank(row, customerHeaders);
                if (customer == null || !matches(customer, customerKey, displayName)) {
                    continue;
                }
                String txnType = CreditRiskParseSupport.firstNonBlank(row, txnHeaders);
                if (txnType == null || !CustomerLedgerExcelUploadValidation.isFailureTransactionType(txnType)) {
                    continue;
                }
                LocalDate payDate = null;
                String dateStr = CreditRiskParseSupport.firstNonBlank(row, dateHeaders);
                if (dateStr != null) {
                    payDate = CreditRiskParseSupport.parseInvoiceDate(dateStr);
                }
                if (payDate != null && ChronoUnit.DAYS.between(payDate, today) > windowDays) {
                    continue;
                }
                count++;
            }
        }
        return count;
    }

    private static UploadedExcelSheet parseCustomerLedgerSheet(Sheet sheet, DataFormatter formatter) {
        int headerRowIndex = findCustomerLedgerHeaderRow(sheet, formatter);
        if (headerRowIndex < 0) {
            return new UploadedExcelSheet(sheet.getSheetName(), List.of(), List.of());
        }
        Row headerRow = sheet.getRow(headerRowIndex);
        int lastCell = Math.max(headerRow.getLastCellNum(), 0);
        List<String> headers = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        for (int c = 0; c < lastCell; c++) {
            Cell cell = headerRow.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            String header = cell == null ? "" : formatter.formatCellValue(cell).trim();
            if (header.isBlank()) {
                header = "Column " + (c + 1);
            }
            int count = seen.getOrDefault(header, 0);
            seen.put(header, count + 1);
            if (count > 0) {
                header = header + " (" + (count + 1) + ")";
            }
            headers.add(header);
        }
        List<Map<String, String>> rows = new ArrayList<>();
        for (int r = headerRowIndex + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            Map<String, String> map = new LinkedHashMap<>();
            boolean any = false;
            for (int c = 0; c < headers.size(); c++) {
                String header = headers.get(c);
                Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                String v = cellValue(cell, header, formatter);
                if (!v.isEmpty()) {
                    any = true;
                }
                map.put(header, v);
            }
            if (any) {
                rows.add(map);
            }
        }
        return new UploadedExcelSheet(sheet.getSheetName(), headers, rows);
    }

    private static int findCustomerLedgerHeaderRow(Sheet sheet, DataFormatter formatter) {
        int max = Math.min(sheet.getLastRowNum(), 30);
        for (int i = 0; i <= max; i++) {
            Row row = sheet.getRow(i);
            if (row == null) {
                continue;
            }
            List<String> headers = new ArrayList<>();
            for (Cell cell : row) {
                if (cell != null) {
                    String h = formatter.formatCellValue(cell).trim();
                    if (!h.isBlank()) {
                        headers.add(h);
                    }
                }
            }
            if (ExcelUploadHeaderRules.isCustomerLedgerHeaderRow(headers)) {
                return i;
            }
        }
        return -1;
    }

    private static String cellValue(Cell cell, String header, DataFormatter formatter) {
        if (ExcelUploadHeaderRules.isVoucherDateHeader(header)
                || ExcelUploadHeaderRules.isAccountingDateHeader(header)) {
            return CreditRiskParseSupport.formatExcelDateCell(cell, formatter);
        }
        if (cell == null) {
            return "";
        }
        return formatter.formatCellValue(cell).trim();
    }

    private static boolean matches(String customer, String key, String displayName) {
        String rowKey = CustomerIdentity.normalizeKey(customer);
        if (rowKey.equals(key)) {
            return true;
        }
        return CustomerIdentity.matchesFuzzy(customer, displayName)
                || CustomerIdentity.matchesFuzzy(customer, key);
    }
}
