package org.example.drive;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.example.payment.CustomerNotes;
import org.example.upload.ExcelUploadHeaderRules;
import org.example.upload.PoiSecurityLimits;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Reads customer name + phone (+ optional) + next payment date (+ optional notes) from a Drive .xlsx.
 */
public final class PaymentDateWorkbookParser {

    private static final DataFormatter FORMATTER = new DataFormatter(Locale.ROOT);

    private PaymentDateWorkbookParser() {
    }

    public static PaymentDateWorkbookParseResult parse(byte[] bytes, String preferredSheetName) throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Drive file is empty.");
        }
        PoiSecurityLimits.apply();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = selectSheet(workbook, preferredSheetName);
            int headerRowIndex = findHeaderRow(sheet);
            Row headerRow = sheet.getRow(headerRowIndex);
            if (headerRow == null) {
                throw new IllegalArgumentException("No header row found. Put Customer Name and Next Payment Date in row 1.");
            }
            int lastCell = Math.max(headerRow.getLastCellNum(), 0);
            List<String> headers = new ArrayList<>();
            for (int i = 0; i < lastCell; i++) {
                Cell cell = headerRow.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                String header = cell == null ? "" : FORMATTER.formatCellValue(cell).trim();
                headers.add(header);
            }
            int nameCol = indexOfNameHeader(headers);
            int phoneCol = indexOfPhoneHeader(headers);
            int dateCol = indexOfDateHeader(headers);
            int noteCol = indexOfNoteHeader(headers);
            if (nameCol < 0 || dateCol < 0) {
                throw new IllegalArgumentException(
                        "Sheet \"" + sheet.getSheetName()
                                + "\" needs columns for Customer Name and Next Payment Date (optional: Phone Number, Notes).");
            }

            List<PaymentDateWorkbookRow> rows = new ArrayList<>();
            List<DriveSyncRowIssue> invalidDates = new ArrayList<>();
            for (int rowIndex = headerRowIndex + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) {
                    continue;
                }
                String name = cellText(row, nameCol);
                if (name.isBlank() || name.equalsIgnoreCase("total")) {
                    continue;
                }
                String phone = phoneCol >= 0 ? cellText(row, phoneCol) : "";
                String note = noteCol >= 0 ? CustomerNotes.clip(cellText(row, noteCol)) : "";
                Optional<String> parsedDate = PaymentDateCellParser.fromCell(row.getCell(dateCol, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL));
                if (parsedDate.isEmpty()) {
                    invalidDates.add(new DriveSyncRowIssue(
                            rowIndex + 1,
                            name,
                            "Invalid date (use DD-MM, DD-MMM-YY, or a real Excel date)"));
                    if (note.isBlank() && phone.isBlank()) {
                        continue;
                    }
                    rows.add(new PaymentDateWorkbookRow(rowIndex + 1, name, phone, "", note));
                    continue;
                }
                String date = parsedDate.get();
                if (date.isBlank() && note.isBlank() && phone.isBlank()) {
                    continue;
                }
                rows.add(new PaymentDateWorkbookRow(rowIndex + 1, name, phone, date, note));
            }
            return new PaymentDateWorkbookParseResult(sheet.getSheetName(), List.copyOf(rows), List.copyOf(invalidDates));
        }
    }

    static Sheet selectSheet(Workbook workbook, String preferredSheetName) {
        if (preferredSheetName != null && !preferredSheetName.isBlank()) {
            Sheet named = workbook.getSheet(preferredSheetName);
            if (named == null) {
                throw new IllegalArgumentException("Sheet \"" + preferredSheetName + "\" was not found in the Drive file.");
            }
            return named;
        }
        Sheet firstMatch = null;
        for (Sheet sheet : workbook) {
            int headerRowIndex = findHeaderRow(sheet);
            Row headerRow = sheet.getRow(headerRowIndex);
            if (headerRow == null) {
                continue;
            }
            List<String> headers = headersOf(headerRow);
            if (indexOfNameHeader(headers) >= 0 && indexOfDateHeader(headers) >= 0) {
                firstMatch = sheet;
                break;
            }
        }
        if (firstMatch != null) {
            return firstMatch;
        }
        if (workbook.getNumberOfSheets() == 0) {
            throw new IllegalArgumentException("Workbook has no sheets.");
        }
        return workbook.getSheetAt(0);
    }

    static List<String> headersOf(Row headerRow) {
        int lastCell = Math.max(headerRow.getLastCellNum(), 0);
        List<String> headers = new ArrayList<>();
        for (int i = 0; i < lastCell; i++) {
            Cell cell = headerRow.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            headers.add(cell == null ? "" : FORMATTER.formatCellValue(cell).trim());
        }
        return headers;
    }

    static int findHeaderRow(Sheet sheet) {
        int firstRow = sheet.getFirstRowNum();
        int lastRow = Math.min(sheet.getLastRowNum(), firstRow + 20);
        for (int rowIndex = firstRow; rowIndex <= lastRow; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }
            List<String> headers = headersOf(row);
            if (indexOfNameHeader(headers) >= 0) {
                return rowIndex;
            }
        }
        return firstRow;
    }

    static int indexOfNameHeader(List<String> headers) {
        for (int i = 0; i < headers.size(); i++) {
            String header = headers.get(i);
            if (ExcelUploadHeaderRules.isCustomerHeader(header)
                    && !ExcelUploadHeaderRules.isPhoneHeader(header)
                    && !isNoteHeader(header)) {
                return i;
            }
        }
        for (int i = 0; i < headers.size(); i++) {
            String n = headers.get(i).trim().toLowerCase(Locale.ROOT);
            if (n.equals("name") || n.equals("party") || n.equals("party name")) {
                return i;
            }
        }
        return -1;
    }

    static int indexOfPhoneHeader(List<String> headers) {
        for (int i = 0; i < headers.size(); i++) {
            if (ExcelUploadHeaderRules.isPhoneHeader(headers.get(i))) {
                return i;
            }
        }
        return -1;
    }

    static int indexOfDateHeader(List<String> headers) {
        int fallback = -1;
        for (int i = 0; i < headers.size(); i++) {
            if (!isNextPaymentDateHeader(headers.get(i))) {
                continue;
            }
            String n = headers.get(i).trim().toLowerCase(Locale.ROOT);
            if (n.contains("next")) {
                return i;
            }
            if (fallback < 0) {
                fallback = i;
            }
        }
        return fallback;
    }

    static int indexOfNoteHeader(List<String> headers) {
        for (int i = 0; i < headers.size(); i++) {
            if (isNoteHeader(headers.get(i))) {
                return i;
            }
        }
        return -1;
    }

    static boolean isNoteHeader(String header) {
        if (header == null) {
            return false;
        }
        String n = header.trim().toLowerCase(Locale.ROOT);
        if (n.isEmpty() || ExcelUploadHeaderRules.isPhoneHeader(header)) {
            return false;
        }
        return n.equals("note")
                || n.equals("notes")
                || n.equals("remark")
                || n.equals("remarks")
                || n.equals("customer note")
                || n.equals("customer notes")
                || n.contains("note");
    }

    static boolean isNextPaymentDateHeader(String header) {
        if (header == null) {
            return false;
        }
        String n = header.trim().toLowerCase(Locale.ROOT);
        if (n.contains("invoice") || n.contains("order") || n.contains("last")) {
            return false;
        }
        if ((n.contains("next") && n.contains("date"))
                || (n.contains("payment") && n.contains("date"))
                || n.contains("due date")
                || n.equals("next due")
                || n.equals("due")
                || n.equals("date")) {
            return true;
        }
        return false;
    }

    static String cellText(Row row, int col) {
        Cell cell = row.getCell(col, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        return cell == null ? "" : FORMATTER.formatCellValue(cell).trim();
    }
}
