package org.example.drive;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.example.customer.CustomerPhoneNumbers;
import org.example.payment.CustomerNotes;
import org.example.payment.DriveSheetCustomer;
import org.example.payment.PaymentDateOverride;
import org.example.upload.ExcelUploadHeaderRules;
import org.example.upload.PoiSecurityLimits;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Writes app payment data into a Drive workbook, appends missing outstanding customers,
 * and removes rows for customers no longer on Outstanding Due.
 */
public final class PaymentDateWorkbookWriter {

    private PaymentDateWorkbookWriter() {
    }

    public record Result(
            byte[] bytes,
            String sheetName,
            List<SheetCellUpdate> cellUpdates,
            List<SheetRowDelete> rowDeletes,
            int updatedRows,
            int insertedRows,
            int removedRows,
            int reorderedRows,
            List<String> notFoundCustomers
    ) {
        public boolean hasChanges() {
            return updatedRows > 0 || insertedRows > 0 || removedRows > 0 || reorderedRows > 0;
        }
    }

    private record ColumnLayout(
            int idCol,
            int nameCol,
            int phoneCol,
            int amountCol,
            int dateCol,
            int noteCol
    ) {
    }

    private record WriteResult(ColumnLayout layout, boolean changed) {
    }

    public static Result applyUpdates(
            byte[] bytes,
            String preferredSheetName,
            List<DriveSheetCustomer> sheetCustomers,
            Map<String, PaymentDateOverride> allCustomersByKey
    ) throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Drive file is empty.");
        }
        List<DriveSheetCustomer> customers = sheetCustomers == null ? List.of() : sheetCustomers;
        Map<String, PaymentDateOverride> allByKey = allCustomersByKey == null ? Map.of() : allCustomersByKey;
        Set<String> activeKeys = new HashSet<>();
        Map<String, DriveSheetCustomer> sheetCustomerByKey = new LinkedHashMap<>();
        Map<String, PaymentDateOverride> outstandingByKey = new LinkedHashMap<>();
        for (DriveSheetCustomer entry : customers) {
            if (entry.customerKey() == null || entry.customerKey().isBlank()) {
                continue;
            }
            activeKeys.add(entry.customerKey());
            sheetCustomerByKey.put(entry.customerKey(), entry);
            outstandingByKey.put(entry.customerKey(), entry.customer());
        }

        List<SheetCellUpdate> cellUpdates = new ArrayList<>();
        List<SheetRowDelete> rowDeletes = new ArrayList<>();
        Set<String> matchedKeys = new HashSet<>();

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
            ColumnLayout layout = readLayout(headers);
            if (layout.nameCol() < 0 || layout.dateCol() < 0) {
                throw new IllegalArgumentException("Drive workbook needs Customer Name and Next Payment Date columns.");
            }

            List<Integer> rowsToRemove = new ArrayList<>();
            for (int rowIndex = headerRowIndex + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) {
                    continue;
                }
                PaymentDateWorkbookRow workbookRow = readWorkbookRow(row, layout, rowIndex + 1);
                if (workbookRow.customerName().isBlank() || workbookRow.customerName().equalsIgnoreCase("total")) {
                    continue;
                }
                Optional<PaymentDateOverride> appCustomer = DriveCustomerMatcher.match(workbookRow, allByKey);
                if (appCustomer.isEmpty()) {
                    continue;
                }
                String customerKey = appCustomer.get().customerKey();
                if (!activeKeys.contains(customerKey)) {
                    rowsToRemove.add(rowIndex);
                    rowDeletes.add(new SheetRowDelete(rowIndex));
                }
            }

            removeRowsBottomUp(sheet, rowsToRemove);
            int removedRows = rowsToRemove.size();

            int updatedRows = 0;
            for (int rowIndex = headerRowIndex + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) {
                    continue;
                }
                PaymentDateWorkbookRow workbookRow = readWorkbookRow(row, layout, rowIndex + 1);
                if (workbookRow.customerName().isBlank() || workbookRow.customerName().equalsIgnoreCase("total")) {
                    continue;
                }
                if (DriveCustomerMatcher.isAmbiguous(workbookRow, outstandingByKey)) {
                    continue;
                }
                Optional<PaymentDateOverride> matched = DriveCustomerMatcher.match(workbookRow, outstandingByKey);
                if (matched.isEmpty()) {
                    continue;
                }
                PaymentDateOverride customer = matched.get();
                DriveSheetCustomer sheetCustomer = sheetCustomerByKey.get(customer.customerKey());
                if (sheetCustomer == null) {
                    continue;
                }
                matchedKeys.add(customer.customerKey());
                WriteResult afterWrite = writeCustomerToRow(
                        headerRow, headers, layout, headerRowIndex, rowIndex, row, sheetCustomer, cellUpdates);
                if (afterWrite.changed()) {
                    updatedRows++;
                }
                layout = afterWrite.layout();
            }

            List<DriveSheetCustomer> toInsert = customers.stream()
                    .filter(entry -> entry.customerKey() != null && !entry.customerKey().isBlank())
                    .filter(entry -> !matchedKeys.contains(entry.customerKey()))
                    .toList();

            int insertedRows = 0;
            int nextRowIndex = Math.max(sheet.getLastRowNum(), headerRowIndex) + 1;
            for (DriveSheetCustomer sheetCustomer : toInsert) {
                PaymentDateOverride customer = sheetCustomer.customer();
                Row row = sheet.createRow(nextRowIndex);
                String displayName = customer.customerName() != null && !customer.customerName().isBlank()
                        ? customer.customerName()
                        : customer.customerKey();
                row.createCell(layout.nameCol()).setCellValue(displayName);
                cellUpdates.add(new SheetCellUpdate(nextRowIndex, layout.nameCol(), displayName));

                layout = writeCustomerToRow(
                        headerRow, headers, layout, headerRowIndex, nextRowIndex, row, sheetCustomer, cellUpdates).layout();
                matchedKeys.add(customer.customerKey());
                insertedRows++;
                nextRowIndex++;
            }

            boolean reordered = reorderDataRowsByAmount(
                    sheet, headerRowIndex, layout, customers, allByKey, cellUpdates);
            int reorderedRows = reordered ? 1 : 0;

            workbook.write(out);
            List<String> notFound = new ArrayList<>();
            for (DriveSheetCustomer entry : customers) {
                if (!matchedKeys.contains(entry.customerKey())) {
                    notFound.add(entry.customer().customerName());
                }
            }
            return new Result(
                    out.toByteArray(),
                    sheetName,
                    List.copyOf(cellUpdates),
                    List.copyOf(rowDeletes),
                    updatedRows,
                    insertedRows,
                    removedRows,
                    reorderedRows,
                    List.copyOf(notFound));
        }
    }

    private static ColumnLayout readLayout(List<String> headers) {
        return new ColumnLayout(
                PaymentDateWorkbookParser.indexOfCustomerIdHeader(headers),
                PaymentDateWorkbookParser.indexOfNameHeader(headers),
                PaymentDateWorkbookParser.indexOfPhoneHeader(headers),
                PaymentDateWorkbookParser.indexOfOutstandingAmountHeader(headers),
                PaymentDateWorkbookParser.indexOfDateHeader(headers),
                PaymentDateWorkbookParser.indexOfNoteHeader(headers)
        );
    }

    private static PaymentDateWorkbookRow readWorkbookRow(Row row, ColumnLayout layout, int excelRow) {
        String customerKey = layout.idCol() >= 0 ? PaymentDateWorkbookParser.cellText(row, layout.idCol()) : "";
        String name = PaymentDateWorkbookParser.cellText(row, layout.nameCol());
        String phone = layout.phoneCol() >= 0 ? PaymentDateWorkbookParser.cellText(row, layout.phoneCol()) : "";
        return new PaymentDateWorkbookRow(excelRow, customerKey, name, phone, "", "");
    }

    private static void removeRowsBottomUp(Sheet sheet, List<Integer> rowsToRemove) {
        rowsToRemove.stream()
                .sorted(Comparator.reverseOrder())
                .forEach(rowIndex -> {
                    int lastRow = sheet.getLastRowNum();
                    if (rowIndex < lastRow) {
                        sheet.shiftRows(rowIndex + 1, lastRow, -1);
                    } else {
                        Row row = sheet.getRow(rowIndex);
                        if (row != null) {
                            sheet.removeRow(row);
                        }
                    }
                });
    }

    private static WriteResult writeCustomerToRow(
            Row headerRow,
            List<String> headers,
            ColumnLayout layout,
            int headerRowIndex,
            int rowIndex,
            Row row,
            DriveSheetCustomer sheetCustomer,
            List<SheetCellUpdate> cellUpdates
    ) {
        PaymentDateOverride customer = sheetCustomer.customer();
        int idCol = layout.idCol();
        int nameCol = layout.nameCol();
        int phoneCol = layout.phoneCol();
        int amountCol = layout.amountCol();
        int dateCol = layout.dateCol();
        int noteCol = layout.noteCol();
        boolean rowChanged = false;

        String customerKey = customer.customerKey() == null ? "" : customer.customerKey().trim();
        int targetIdCol = resolveCustomerIdColumn(headerRow, headers, layout, headerRowIndex, cellUpdates);
        if (targetIdCol >= 0) {
            idCol = targetIdCol;
            String currentId = PaymentDateWorkbookParser.cellText(row, idCol);
            if (!Objects.equals(currentId, customerKey) && !customerKey.isBlank()) {
                row.getCell(idCol, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).setCellValue(customerKey);
                cellUpdates.add(new SheetCellUpdate(rowIndex, idCol, customerKey));
                rowChanged = true;
            }
        }

        String appPhone = customer.phoneNumber() == null ? "" : customer.phoneNumber().trim();
        int targetPhoneCol = resolvePhoneColumn(
                headerRow, headers, nameCol, phoneCol, amountCol, dateCol, noteCol, idCol, headerRowIndex, cellUpdates);
        if (targetPhoneCol >= 0) {
            phoneCol = targetPhoneCol;
            String excelPhone = CustomerPhoneNumbers.driveExcelText(appPhone);
            String currentPhone = PaymentDateWorkbookParser.cellText(row, phoneCol);
            boolean phonesMatch = appPhone.isBlank() && currentPhone.isBlank()
                    || CustomerPhoneNumbers.sameCanonicalPhone(currentPhone, appPhone);
            if (!phonesMatch) {
                Cell phoneCell = row.getCell(phoneCol, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                if (excelPhone.isBlank()) {
                    phoneCell.setBlank();
                } else {
                    phoneCell.setCellValue(excelPhone);
                }
                cellUpdates.add(new SheetCellUpdate(rowIndex, phoneCol, excelPhone));
                rowChanged = true;
            }
        }

        int targetAmountCol = layout.amountCol();
        if (targetAmountCol >= 0) {
            amountCol = targetAmountCol;
            String excelAmount = formatOutstandingAmount(sheetCustomer.outstandingAmount());
            String currentAmount = PaymentDateWorkbookParser.cellText(row, amountCol);
            if (!Objects.equals(currentAmount, excelAmount)) {
                Cell amountCell = row.getCell(amountCol, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                if (excelAmount.isBlank()) {
                    amountCell.setBlank();
                } else {
                    amountCell.setCellValue(excelAmount);
                }
                cellUpdates.add(new SheetCellUpdate(rowIndex, amountCol, excelAmount));
                rowChanged = true;
            }
        }

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

        return new WriteResult(new ColumnLayout(idCol, nameCol, phoneCol, amountCol, dateCol, noteCol), rowChanged);
    }

    private static String formatOutstandingAmount(double amount) {
        if (amount == 0.0) {
            return "0";
        }
        if (Math.rint(amount) == amount) {
            return String.valueOf((long) amount);
        }
        return String.valueOf(amount);
    }

    private record BufferedRow(String customerKey, List<String> cellValues) {
    }

    private static boolean reorderDataRowsByAmount(
            Sheet sheet,
            int headerRowIndex,
            ColumnLayout layout,
            List<DriveSheetCustomer> orderedCustomers,
            Map<String, PaymentDateOverride> allByKey,
            List<SheetCellUpdate> cellUpdates
    ) {
        if (orderedCustomers.isEmpty()) {
            return false;
        }
        Map<String, Integer> desiredOrder = new LinkedHashMap<>();
        for (int i = 0; i < orderedCustomers.size(); i++) {
            desiredOrder.put(orderedCustomers.get(i).customerKey(), i);
        }

        int lastCol = Math.max(sheet.getRow(headerRowIndex).getLastCellNum(), 1);
        List<BufferedRow> activeRows = new ArrayList<>();
        List<BufferedRow> manualRows = new ArrayList<>();
        List<String> currentActiveKeys = new ArrayList<>();

        for (int rowIndex = headerRowIndex + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }
            PaymentDateWorkbookRow workbookRow = readWorkbookRow(row, layout, rowIndex + 1);
            if (workbookRow.customerName().isBlank() || workbookRow.customerName().equalsIgnoreCase("total")) {
                continue;
            }
            BufferedRow buffered = copyRow(row, lastCol);
            Optional<PaymentDateOverride> matched = DriveCustomerMatcher.match(workbookRow, allByKey);
            if (matched.isPresent() && desiredOrder.containsKey(matched.get().customerKey())) {
                String key = matched.get().customerKey();
                activeRows.add(new BufferedRow(key, buffered.cellValues()));
                currentActiveKeys.add(key);
            } else {
                manualRows.add(new BufferedRow("", buffered.cellValues()));
            }
        }

        activeRows.sort(Comparator.comparingInt(row -> desiredOrder.getOrDefault(row.customerKey(), Integer.MAX_VALUE)));

        boolean needsReorder = !currentActiveKeys.equals(activeRows.stream().map(BufferedRow::customerKey).toList());
        if (!needsReorder) {
            return false;
        }

        while (sheet.getLastRowNum() > headerRowIndex) {
            Row row = sheet.getRow(sheet.getLastRowNum());
            if (row != null) {
                sheet.removeRow(row);
            }
        }

        cellUpdates.clear();
        Row headerRow = sheet.getRow(headerRowIndex);
        if (headerRow != null) {
            for (int col = 0; col < Math.max(headerRow.getLastCellNum(), 1); col++) {
                String headerValue = PaymentDateWorkbookParser.cellText(headerRow, col);
                if (!headerValue.isBlank()) {
                    cellUpdates.add(new SheetCellUpdate(headerRowIndex, col, headerValue));
                }
            }
        }
        int writeRowIndex = headerRowIndex + 1;
        for (BufferedRow buffered : activeRows) {
            pasteRow(sheet.createRow(writeRowIndex), buffered.cellValues(), writeRowIndex, cellUpdates);
            writeRowIndex++;
        }
        for (BufferedRow buffered : manualRows) {
            pasteRow(sheet.createRow(writeRowIndex), buffered.cellValues(), writeRowIndex, cellUpdates);
            writeRowIndex++;
        }
        return true;
    }

    private static BufferedRow copyRow(Row row, int lastCol) {
        List<String> values = new ArrayList<>();
        for (int col = 0; col < lastCol; col++) {
            values.add(PaymentDateWorkbookParser.cellText(row, col));
        }
        return new BufferedRow("", values);
    }

    private static void pasteRow(
            Row row,
            List<String> values,
            int rowIndex,
            List<SheetCellUpdate> cellUpdates
    ) {
        for (int col = 0; col < values.size(); col++) {
            String value = values.get(col);
            if (value == null || value.isBlank()) {
                continue;
            }
            row.createCell(col).setCellValue(value);
            cellUpdates.add(new SheetCellUpdate(rowIndex, col, value));
        }
    }

    private static int resolveCustomerIdColumn(
            Row headerRow,
            List<String> headers,
            ColumnLayout layout,
            int headerRowIndex,
            List<SheetCellUpdate> cellUpdates
    ) {
        if (layout.idCol() >= 0) {
            return layout.idCol();
        }
        int candidate = layout.nameCol() > 0 ? 0 : Math.max(headerRow.getLastCellNum(), 0);
        String existingHeader = candidate < headers.size() ? headers.get(candidate).trim() : "";
        if (!existingHeader.isBlank() && !ExcelUploadHeaderRules.isCustomerIdHeader(existingHeader)) {
            return -1;
        }
        if (existingHeader.isBlank()) {
            Cell headerCell = headerRow.getCell(candidate, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
            headerCell.setCellValue("Customer ID");
            cellUpdates.add(new SheetCellUpdate(headerRowIndex, candidate, "Customer ID"));
        }
        return candidate;
    }

    private static int resolvePhoneColumn(
            Row headerRow,
            List<String> headers,
            int nameCol,
            int phoneCol,
            int amountCol,
            int dateCol,
            int noteCol,
            int idCol,
            int headerRowIndex,
            List<SheetCellUpdate> cellUpdates
    ) {
        if (phoneCol >= 0) {
            return phoneCol;
        }
        int candidate = nameCol + 1;
        if (candidate == dateCol || candidate == noteCol || candidate == idCol || candidate == amountCol) {
            return -1;
        }
        String existingHeader = candidate < headers.size() ? headers.get(candidate).trim() : "";
        if (!existingHeader.isBlank() && !ExcelUploadHeaderRules.isPhoneHeader(existingHeader)) {
            return -1;
        }
        if (existingHeader.isBlank()) {
            Cell headerCell = headerRow.getCell(candidate, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
            headerCell.setCellValue("Phone Number");
            cellUpdates.add(new SheetCellUpdate(headerRowIndex, candidate, "Phone Number"));
        }
        return candidate;
    }
}
