package org.example.drive;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.example.payment.DriveSheetCustomer;
import org.example.payment.PaymentDateOverride;
import org.example.payment.PaymentDateOverrideCopy;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentDateWorkbookWriterTest {

    private static DriveSheetCustomer sheetCustomer(PaymentDateOverride customer, double amount) {
        return new DriveSheetCustomer(customer, amount);
    }

    @Test
    void applyUpdates_writesMonthAbbrevDateForMatchedCustomer() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Dates");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Customer Name");
            header.createCell(1).setCellValue("Next Payment Date");

            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("ABC Traders");
            row.createCell(1).setCellValue("01-01");

            workbook.write(out);
            bytes = out.toByteArray();
        }

        PaymentDateOverride customer = PaymentDateOverrideCopy.copy(
                PaymentDateOverrideCopy.newShell("abc traders", "ABC Traders"),
                null, null, "19-08", null, null, null, null, null, null, null, null, null, null, null, null, null
        );
        Map<String, PaymentDateOverride> all = DriveCustomerMatcher.indexByKey(List.of(customer));

        PaymentDateWorkbookWriter.Result result = PaymentDateWorkbookWriter.applyUpdates(
                bytes, "", List.of(sheetCustomer(customer, 12000)), all);
        assertEquals(1, result.updatedRows());
        assertEquals("Dates", result.sheetName());
        assertTrue(result.notFoundCustomers().isEmpty());

        PaymentDateWorkbookParseResult parsed = PaymentDateWorkbookParser.parse(result.bytes(), "");
        assertEquals(1, parsed.rows().size());
        assertEquals("19-08", parsed.rows().getFirst().nextPaymentDate());
        assertEquals("abc traders", parsed.rows().getFirst().customerKey());
    }

    @Test
    void applyUpdates_sortsRowsByOutstandingAmountHighToLow() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Dates");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Customer ID");
            header.createCell(1).setCellValue("Customer Name");
            header.createCell(2).setCellValue("Phone Number");
            header.createCell(3).setCellValue("Next Payment Date");
            header.createCell(4).setCellValue("Notes");

            Row low = sheet.createRow(1);
            low.createCell(0).setCellValue("low due co");
            low.createCell(1).setCellValue("Low Due Co");
            low.createCell(3).setCellValue("01-01");

            Row high = sheet.createRow(2);
            high.createCell(0).setCellValue("high due co");
            high.createCell(1).setCellValue("High Due Co");
            high.createCell(3).setCellValue("02-02");

            workbook.write(out);
            bytes = out.toByteArray();
        }

        PaymentDateOverride low = PaymentDateOverrideCopy.copy(
                PaymentDateOverrideCopy.newShell("low due co", "Low Due Co"),
                null, null, "01-01", null, null, null, null, null, null, null, null, null, null, null, null, null
        );
        PaymentDateOverride high = PaymentDateOverrideCopy.copy(
                PaymentDateOverrideCopy.newShell("high due co", "High Due Co"),
                null, null, "02-02", null, null, null, null, null, null, null, null, null, null, null, null, null
        );
        Map<String, PaymentDateOverride> all = DriveCustomerMatcher.indexByKey(List.of(low, high));
        List<DriveSheetCustomer> sheetCustomers = List.of(
                sheetCustomer(high, 50000),
                sheetCustomer(low, 1000));

        PaymentDateWorkbookWriter.Result result = PaymentDateWorkbookWriter.applyUpdates(
                bytes, "", sheetCustomers, all);
        assertEquals(1, result.reorderedRows());

        PaymentDateWorkbookParseResult parsed = PaymentDateWorkbookParser.parse(result.bytes(), "");
        assertEquals(2, parsed.rows().size());
        assertEquals("High Due Co", parsed.rows().get(0).customerName());
        assertEquals("Low Due Co", parsed.rows().get(1).customerName());
    }

    @Test
    void applyUpdates_keepsRetainedCustomerWithZeroAmount() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Dates");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Customer ID");
            header.createCell(1).setCellValue("Customer Name");
            header.createCell(2).setCellValue("Phone Number");
            header.createCell(3).setCellValue("Next Payment Date");
            header.createCell(4).setCellValue("Notes");

            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("big due co");
            row.createCell(1).setCellValue("Big Due Co");
            row.createCell(3).setCellValue("10-10");

            workbook.write(out);
            bytes = out.toByteArray();
        }

        PaymentDateOverride big = PaymentDateOverrideCopy.copy(
                PaymentDateOverrideCopy.newShell("big due co", "Big Due Co"),
                null, null, "10-10", null, null, null, null, null, null, null, null, null, null, null, null, null
        );
        PaymentDateOverride retained = PaymentDateOverrideCopy.withRetained(
                PaymentDateOverrideCopy.copy(
                        PaymentDateOverrideCopy.newShell("cash customer", "Cash Customer"),
                        null, null, "15-08", "9876543210", null, null, null, null, null, null, null, null, null, null, null, null
                ),
                true,
                "staff"
        );
        Map<String, PaymentDateOverride> all = DriveCustomerMatcher.indexByKey(List.of(big, retained));
        List<DriveSheetCustomer> sheetCustomers = List.of(
                sheetCustomer(big, 25000),
                sheetCustomer(retained, 0));

        PaymentDateWorkbookWriter.Result result = PaymentDateWorkbookWriter.applyUpdates(
                bytes, "", sheetCustomers, all);
        assertEquals(1, result.insertedRows());

        PaymentDateWorkbookParseResult parsed = PaymentDateWorkbookParser.parse(result.bytes(), "");
        assertEquals(2, parsed.rows().size());
        assertEquals("Big Due Co", parsed.rows().get(0).customerName());
        assertEquals("Cash Customer", parsed.rows().get(1).customerName());
    }

    @Test
    void applyUpdates_writesLatestNoteForMatchedCustomer() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Dates");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Customer Name");
            header.createCell(1).setCellValue("Next Payment Date");
            header.createCell(2).setCellValue("Notes");

            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("ABC Traders");
            row.createCell(1).setCellValue("19-Aug-26");
            row.createCell(2).setCellValue("old note");

            workbook.write(out);
            bytes = out.toByteArray();
        }

        PaymentDateOverride customer = PaymentDateOverrideCopy.copy(
                PaymentDateOverrideCopy.newShell("abc traders", "ABC Traders"),
                null, null, "19-08", null, null, null, null, null, null, null, null, null,
                List.of(org.example.payment.CustomerNotes.newDriveNote("Paid half")),
                null, null, null
        );
        Map<String, PaymentDateOverride> all = DriveCustomerMatcher.indexByKey(List.of(customer));

        PaymentDateWorkbookWriter.Result result = PaymentDateWorkbookWriter.applyUpdates(
                bytes, "", List.of(sheetCustomer(customer, 5000)), all);
        assertEquals(1, result.updatedRows());
        assertTrue(result.notFoundCustomers().isEmpty());

        PaymentDateWorkbookParseResult parsed = PaymentDateWorkbookParser.parse(result.bytes(), "");
        assertEquals(1, parsed.rows().size());
        assertEquals("Paid half", parsed.rows().getFirst().note());
    }

    @Test
    void applyUpdates_writesPhoneForMatchedCustomer() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Dates");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Customer Name");
            header.createCell(1).setCellValue("Phone Number");
            header.createCell(2).setCellValue("Next Payment Date");

            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("ABC Traders");
            row.createCell(2).setCellValue("19-08");

            workbook.write(out);
            bytes = out.toByteArray();
        }

        PaymentDateOverride customer = PaymentDateOverrideCopy.copy(
                PaymentDateOverrideCopy.newShell("abc traders", "ABC Traders"),
                null, null, "19-08", "9876543210", null, null, null, null, null, null, null, null, null, null, null, null
        );
        Map<String, PaymentDateOverride> all = DriveCustomerMatcher.indexByKey(List.of(customer));

        PaymentDateWorkbookWriter.Result result = PaymentDateWorkbookWriter.applyUpdates(
                bytes, "", List.of(sheetCustomer(customer, 8000)), all);
        assertEquals(1, result.updatedRows());
        assertTrue(result.notFoundCustomers().isEmpty());

        PaymentDateWorkbookParseResult parsed = PaymentDateWorkbookParser.parse(result.bytes(), "");
        assertEquals(1, parsed.rows().size());
        assertEquals("9876543210", parsed.rows().getFirst().phoneNumber());
    }

    @Test
    void applyUpdates_insertsMissingOutstandingCustomer() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Dates");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Customer Name");
            header.createCell(1).setCellValue("Phone Number");
            header.createCell(2).setCellValue("Next Payment Date");
            header.createCell(3).setCellValue("Notes");

            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("ABC Traders");
            row.createCell(2).setCellValue("19-08");

            workbook.write(out);
            bytes = out.toByteArray();
        }

        PaymentDateOverride existing = PaymentDateOverrideCopy.copy(
                PaymentDateOverrideCopy.newShell("abc traders", "ABC Traders"),
                null, null, "19-08", null, null, null, null, null, null, null, null, null, null, null, null, null
        );
        PaymentDateOverride missing = PaymentDateOverrideCopy.copy(
                PaymentDateOverrideCopy.newShell("xyz stores", "XYZ Stores"),
                null, null, "20-08", "9876501234", null, null, null, null, null, null, null, null, null, null, null, null
        );
        Map<String, PaymentDateOverride> all = DriveCustomerMatcher.indexByKey(List.of(existing, missing));

        PaymentDateWorkbookWriter.Result result = PaymentDateWorkbookWriter.applyUpdates(
                bytes, "",
                List.of(sheetCustomer(existing, 30000), sheetCustomer(missing, 12000)),
                all);
        assertEquals(1, result.insertedRows());
        assertTrue(result.notFoundCustomers().isEmpty());

        PaymentDateWorkbookParseResult parsed = PaymentDateWorkbookParser.parse(result.bytes(), "");
        assertEquals(2, parsed.rows().size());
        assertEquals("ABC Traders", parsed.rows().get(0).customerName());
        assertEquals("XYZ Stores", parsed.rows().get(1).customerName());
    }

    @Test
    void applyUpdates_removesPaidOffCustomer() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Dates");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Customer ID");
            header.createCell(1).setCellValue("Customer Name");
            header.createCell(2).setCellValue("Next Payment Date");

            Row active = sheet.createRow(1);
            active.createCell(0).setCellValue("abc traders");
            active.createCell(1).setCellValue("ABC Traders");
            active.createCell(2).setCellValue("19-08");

            Row paid = sheet.createRow(2);
            paid.createCell(0).setCellValue("old customer");
            paid.createCell(1).setCellValue("Old Customer");
            paid.createCell(2).setCellValue("01-01");

            workbook.write(out);
            bytes = out.toByteArray();
        }

        PaymentDateOverride active = PaymentDateOverrideCopy.copy(
                PaymentDateOverrideCopy.newShell("abc traders", "ABC Traders"),
                null, null, "19-08", null, null, null, null, null, null, null, null, null, null, null, null, null
        );
        PaymentDateOverride paid = PaymentDateOverrideCopy.copy(
                PaymentDateOverrideCopy.newShell("old customer", "Old Customer"),
                null, null, "01-01", null, null, null, null, null, null, null, null, null, null, null, null, null
        );
        Map<String, PaymentDateOverride> all = DriveCustomerMatcher.indexByKey(List.of(active, paid));

        PaymentDateWorkbookWriter.Result result = PaymentDateWorkbookWriter.applyUpdates(
                bytes, "", List.of(sheetCustomer(active, 9000)), all);
        assertEquals(1, result.removedRows());

        PaymentDateWorkbookParseResult parsed = PaymentDateWorkbookParser.parse(result.bytes(), "");
        assertEquals(1, parsed.rows().size());
        assertEquals("ABC Traders", parsed.rows().getFirst().customerName());
    }

    @Test
    void applyUpdates_writesCanonicalPhoneAsTenDigits() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Dates");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Customer Name");
            header.createCell(1).setCellValue("Phone Number");
            header.createCell(2).setCellValue("Next Payment Date");

            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("ABC Traders");
            row.createCell(1).setCellValue("0000000000");
            row.createCell(2).setCellValue("19-08");

            workbook.write(out);
            bytes = out.toByteArray();
        }

        PaymentDateOverride customer = PaymentDateOverrideCopy.copy(
                PaymentDateOverrideCopy.newShell("abc traders", "ABC Traders"),
                null, null, "19-08", "919876543210", null, null, null, null, null, null, null, null, null, null, null, null
        );
        Map<String, PaymentDateOverride> all = DriveCustomerMatcher.indexByKey(List.of(customer));

        PaymentDateWorkbookWriter.Result result = PaymentDateWorkbookWriter.applyUpdates(
                bytes, "", List.of(sheetCustomer(customer, 15000)), all);
        assertEquals(1, result.updatedRows());

        PaymentDateWorkbookParseResult parsed = PaymentDateWorkbookParser.parse(result.bytes(), "");
        assertEquals("9876543210", parsed.rows().getFirst().phoneNumber());
    }

    @Test
    void applyUpdates_doesNotAddOutstandingAmountColumnForFiveColumnLayout() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Sheet1");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Customer ID");
            header.createCell(1).setCellValue("Customer Name");
            header.createCell(2).setCellValue("Phone Number");
            header.createCell(3).setCellValue("Next Payment Date");
            header.createCell(4).setCellValue("Notes");

            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("abc traders");
            row.createCell(1).setCellValue("ABC Traders");
            row.createCell(3).setCellValue("18-08");

            workbook.write(out);
            bytes = out.toByteArray();
        }

        PaymentDateOverride customer = PaymentDateOverrideCopy.copy(
                PaymentDateOverrideCopy.newShell("abc traders", "ABC Traders"),
                null, null, "19-08", "9876543210", null, null, null, null, null, null, null, null, null, null, null, null
        );
        Map<String, PaymentDateOverride> all = DriveCustomerMatcher.indexByKey(List.of(customer));

        PaymentDateWorkbookWriter.Result result = PaymentDateWorkbookWriter.applyUpdates(
                bytes, "", List.of(sheetCustomer(customer, 25000)), all);

        assertTrue(result.cellUpdates().stream().noneMatch(update -> "Outstanding Amount".equals(update.value())));
        assertTrue(result.cellUpdates().stream().noneMatch(update -> update.colIndex() > 4));

        try (XSSFWorkbook workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(result.bytes()))) {
            Row header = workbook.getSheetAt(0).getRow(0);
            assertEquals("Customer ID", header.getCell(0).getStringCellValue());
            assertEquals("Customer Name", header.getCell(1).getStringCellValue());
            assertEquals("Phone Number", header.getCell(2).getStringCellValue());
            assertEquals("Next Payment Date", header.getCell(3).getStringCellValue());
            assertEquals("Notes", header.getCell(4).getStringCellValue());
            assertEquals(5, header.getLastCellNum());
        }
    }
}
