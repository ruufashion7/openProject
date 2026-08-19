package org.example.drive;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
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
 * Writes app next-payment dates back into a Drive workbook (.xlsx bytes or Google Sheet cells).
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
        Map<String, List<PaymentDateOverride>> byPhone = DriveCustomerMatcher.indexByPhone(customers);
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
            int phoneCol = PaymentDateWorkbookParser.indexOfPhoneHeader(headers);
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
                String phone = phoneCol >= 0 ? PaymentDateWorkbookParser.cellText(row, phoneCol) : "";
                PaymentDateWorkbookRow workbookRow = new PaymentDateWorkbookRow(rowIndex + 1, name, phone, "");
                if (DriveCustomerMatcher.isAmbiguous(workbookRow, byKey, byPhone)) {
                    continue;
                }
                Optional<PaymentDateOverride> matched = DriveCustomerMatcher.match(workbookRow, byKey, byPhone);
                if (matched.isEmpty()) {
                    continue;
                }
                PaymentDateOverride customer = matched.get();
                String nextDate = customer.nextPaymentDate() == null ? "" : customer.nextPaymentDate().trim();
                String excelText = PaymentDateCellParser.toExcelText(nextDate);
                Cell cell = row.getCell(dateCol, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                String current = PaymentDateWorkbookParser.cellText(row, dateCol);
                if (Objects.equals(current, excelText) || (current.isBlank() && excelText.isBlank())) {
                    matchedKeys.add(customer.customerKey());
                    continue;
                }
                if (excelText.isBlank()) {
                    cell.setBlank();
                } else {
                    cell.setCellValue(excelText);
                }
                cellUpdates.add(new SheetCellUpdate(rowIndex, dateCol, excelText));
                matchedKeys.add(customer.customerKey());
                updatedRows++;
            }

            workbook.write(out);
            List<String> notFound = new ArrayList<>();
            for (PaymentDateOverride customer : customers) {
                if (customer.customerKey() != null && !matchedKeys.contains(customer.customerKey())) {
                    notFound.add(customer.customerName());
                }
            }
            return new Result(out.toByteArray(), sheetName, List.copyOf(cellUpdates), updatedRows, List.copyOf(notFound));
        }
    }
}
