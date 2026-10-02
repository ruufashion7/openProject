package org.example.creditrisk;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.example.upload.ExcelUploadHeaderRules;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Shared parse helpers for credit-risk upload rows (mirrors AnalyticsController rules). */
public final class CreditRiskParseSupport {

    private static final List<DateTimeFormatter> INVOICE_DATE_TIME_FORMATTERS = List.of(
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd-MMM-yyyy hh:mm a").toFormatter(Locale.ENGLISH),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd-MMM-yyyy HH:mm").toFormatter(Locale.ENGLISH),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd-MMM-yyyy HH:mm:ss").toFormatter(Locale.ENGLISH)
    );
    private static final List<DateTimeFormatter> INVOICE_DATE_FORMATTERS = List.of(
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd-MMM-yyyy").toFormatter(Locale.ENGLISH),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd/MM/yyyy").toFormatter(Locale.ENGLISH),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d/M/yyyy").toFormatter(Locale.ENGLISH),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("yyyy-MM-dd").toFormatter(Locale.ENGLISH)
    );

    private CreditRiskParseSupport() {
    }

    public static LocalDate parseInvoiceDate(String invoiceDate) {
        if (invoiceDate == null || invoiceDate.isBlank()) {
            return null;
        }
        String normalizedDate = invoiceDate.replaceAll("(?i)-Sept-", "-Sep-");
        for (DateTimeFormatter formatter : INVOICE_DATE_TIME_FORMATTERS) {
            try {
                return LocalDateTime.parse(normalizedDate, formatter).toLocalDate();
            } catch (DateTimeParseException ignored) {
                // next
            }
        }
        for (DateTimeFormatter formatter : INVOICE_DATE_FORMATTERS) {
            try {
                return LocalDate.parse(normalizedDate, formatter);
            } catch (DateTimeParseException ignored) {
                // next
            }
        }
        try {
            double serial = Double.parseDouble(normalizedDate.trim());
            if (DateUtil.isValidExcelDate(serial)) {
                return DateUtil.getLocalDateTime(serial).toLocalDate();
            }
        } catch (NumberFormatException ignored) {
            // not a serial
        }
        return null;
    }

    /** Store ledger / invoice dates as dd-MMM-yyyy for Mongo rows. */
    public static String formatExcelDateCell(Cell cell, org.apache.poi.ss.usermodel.DataFormatter formatter) {
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return "";
        }
        if (cell.getCellType() == CellType.NUMERIC
                || (cell.getCellType() == CellType.FORMULA && cell.getCachedFormulaResultType() == CellType.NUMERIC)) {
            if (DateUtil.isCellDateFormatted(cell)) {
                LocalDate d = cell.getLocalDateTimeCellValue().toLocalDate();
                return d.format(DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH));
            }
            double numeric = cell.getNumericCellValue();
            if (DateUtil.isValidExcelDate(numeric) && numeric > 200 && numeric < 80000) {
                LocalDate d = DateUtil.getLocalDateTime(numeric).toLocalDate();
                return d.format(DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH));
            }
        }
        String text = formatter.formatCellValue(cell).trim();
        LocalDate parsed = parseInvoiceDate(text);
        if (parsed != null) {
            return parsed.format(DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH));
        }
        return text;
    }

    public static double parseAmount(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0.0;
        }
        String cleaned = raw.trim()
                .replace(",", "")
                .replace("₹", "")
                .replace("Rs.", "")
                .replace("Rs", "")
                .replaceAll("[^0-9.\\-]", "");
        if (cleaned.isBlank() || cleaned.equals("-") || cleaned.equals(".")) {
            return 0.0;
        }
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException ex) {
            return 0.0;
        }
    }

    public static String firstNonBlank(Map<String, String> row, List<String> headers) {
        for (String header : headers) {
            String value = row.get(header);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    public static double sumAmounts(Map<String, String> row, List<String> headers) {
        double total = 0.0;
        for (String header : headers) {
            total += parseAmount(row.get(header));
        }
        return total;
    }

    public enum AmountBucket { TOTAL, WITHIN, MID, BEYOND, UNKNOWN }

    public static AmountBucket classifyAmountHeader(String header) {
        if (header == null) {
            return AmountBucket.UNKNOWN;
        }
        String n = header.trim().toLowerCase(Locale.ROOT);
        if (n.contains("total")) {
            return AmountBucket.TOTAL;
        }
        if (n.contains("1-45") || n.contains("0-30") || n.contains("within") || n.contains("0–30") || n.contains("1–45")) {
            return AmountBucket.WITHIN;
        }
        if (n.contains("46-85") || n.contains("31-60") || n.contains("61-90") || n.contains("46–85")) {
            return AmountBucket.MID;
        }
        if (n.contains("85+") || n.contains("91+") || n.contains("121+") || n.contains("beyond") || n.contains("90+")) {
            return AmountBucket.BEYOND;
        }
        if (ExcelUploadHeaderRules.isAmountHeader(header)) {
            return AmountBucket.UNKNOWN;
        }
        return AmountBucket.UNKNOWN;
    }

    public static boolean isVoidOrCancelled(Map<String, String> row, List<String> statusHeaders) {
        for (String header : statusHeaders) {
            String v = row.get(header);
            if (v == null) {
                continue;
            }
            String n = v.trim().toLowerCase(Locale.ROOT);
            if (n.contains("void") || n.contains("cancel") || n.contains("reversed")) {
                return true;
            }
        }
        return false;
    }
}
