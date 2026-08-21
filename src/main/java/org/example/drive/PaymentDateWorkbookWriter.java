package org.example.drive;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.example.payment.CustomerNotes;
import org.example.payment.PaymentDateOverride;
import org.example.upload.PoiSecurityLimits;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Writes app next-payment dates and latest notes back into a Drive workbook.
 */
public final class PaymentDateWorkbookWriter {

    private PaymentDateWorkbookWriter() {
    }

    public record Result(
            byte[] bytes,
            String sheetName,
            List<SheetCellUpdate> cellUpdates,
            int updatedRows,
            List<String> notFoundCustomers
    ) {
    }

    public static Result applyUpdates(
            byte[] bytes,
            String preferredSheetName,
            Collection<PaymentDateOverride> customers
    ) throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Drive file is empty.");
        }
        if (customers == null || customers.isEmpty()) {
            return new Result(bytes, "", List.of(), 0, List.of());
        }

        Map<String, PaymentDateOverride> byKey = DriveCustomerMatcher.indexByKey(customers);
        Set<String> matchedKeys = new HashSet<>();
        List<SheetCellUpdate> cellUpdates = new ArrayList<>();

        PoiSecurityLimits.apply();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = PaymentDateWorkbookParser.selectSheet(workbook, preferredSheetName);
            String sheetName = sheet.getSheetName();
            int headerRowIndex = PaymentDateWorkbookParser.findHeaderRow(sheet);
            Row headerRow = sheet.getRow(headerRowIndex);
            if (headerRow == null) {
                throw new IllegalArgumentException("No header row found in Drive workbook.");
            }
            List<String> headers = PaymentDateWorkbookParser.headersOf(headerRow);
            int nameCol = PaymentDateWorkbookParser.indexOfNameHeader(headers);
            int dateCol = PaymentDateWorkbookParser.indexOfDateHeader(headers);
            int noteCol = PaymentDateWorkbookParser.indexOfNoteHeader(headers);
            if (nameCol < 0 || dateCol < 0) {
                throw new IllegalArgumentException("Drive workbook needs Customer Name and Next Payment Date columns.");
            }

            int updatedRows = 0;
            for (int rowIndex = headerRowIndex + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) {
                    continue;
                }
                String name = PaymentDateWorkbookParser.cellText(row, nameCol);
                if (name.isBlank() || name.equalsIgnoreCase("total")) {
                    continue;
                }
                PaymentDateWorkbookRow workbookRow = new PaymentDateWorkbookRow(rowIndex + 1, name, "", "");
                if (DriveCustomerMatcher.isAmbiguous(workbookRow, byKey)) {
                    continue;
                }
                Optional<PaymentDateOverride> matched = DriveCustomerMatcher.match(workbookRow, byKey);
                if (matched.isEmpty()) {
                    continue;
                }
                PaymentDateOverride customer = matched.get();
                matchedKeys.add(customer.customerKey());
                boolean rowChanged = false;

                String nextDate = customer.nextPaymentDate() == null ? "" : customer.nextPaymentDate().trim();
                if (!nextDate.isBlank()) {
                    String excelText = PaymentDateCellParser.toExcelText(nextDate);
                    Cell dateCell = row.getCell(dateCol, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                    String currentDate = PaymentDateWorkbookParser.cellText(row, dateCol);
                    if (!Objects.equals(currentDate, excelText) && !(currentDate.isBlank() && excelText.isBlank())) {
                        dateCell.setCellValue(excelText);
                        cellUpdates.add(new SheetCellUpdate(rowIndex, dateCol, excelText));
                        rowChanged = true;
                    }
                }

                String latestNote = CustomerNotes.latestText(customer.notes());
                String currentNote = noteCol >= 0 ? PaymentDateWorkbookParser.cellText(row, noteCol) : "";
                if (!Objects.equals(CustomerNotes.normalizeText(currentNote), latestNote)) {
                    if (noteCol < 0) {
                        noteCol = Math.max(headerRow.getLastCellNum(), 0);
                        Cell headerCell = headerRow.createCell(noteCol);
                        headerCell.setCellValue("Notes");
                        cellUpdates.add(new SheetCellUpdate(headerRowIndex, noteCol, "Notes"));
                    }
                    Cell noteCell = row.getCell(noteCol, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                    if (latestNote.isBlank()) {
                        noteCell.setBlank();
                    } else {
                        noteCell.setCellValue(latestNote);
                    }
                    cellUpdates.add(new SheetCellUpdate(rowIndex, noteCol, latestNote));
                    rowChanged = true;
                }

                if (rowChanged) {
                    updatedRows++;
                }
            }

            workbook.write(out);
            List<String> notFound = new ArrayList<>();
            for (PaymentDateOverride customer : customers) {
                if (customer.customerKey() != null && !matchedKeys.contains(customer.customerKey())) {
                    boolean hasDate = customer.nextPaymentDate() != null && !customer.nextPaymentDate().trim().isBlank();
                    boolean hasNotes = customer.notes() != null && !customer.notes().isEmpty();
                    if (hasDate || hasNotes) {
                        notFound.add(customer.customerName());
                    }
                }
            }
            return new Result(out.toByteArray(), sheetName, List.copyOf(cellUpdates), updatedRows, List.copyOf(notFound));
        }
    }
}
