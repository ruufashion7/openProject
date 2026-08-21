package org.example.drive;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.example.payment.PaymentDateOverride;
import org.example.payment.PaymentDateOverrideCopy;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentDateWorkbookWriterTest {

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

        PaymentDateWorkbookWriter.Result result = PaymentDateWorkbookWriter.applyUpdates(bytes, "", List.of(customer));
        assertEquals(1, result.updatedRows());
        assertEquals(1, result.cellUpdates().size());
        assertEquals("Dates", result.sheetName());
        assertTrue(result.notFoundCustomers().isEmpty());

        PaymentDateWorkbookParseResult parsed = PaymentDateWorkbookParser.parse(result.bytes(), "");
        assertEquals(1, parsed.rows().size());
        assertEquals("19-08", parsed.rows().getFirst().nextPaymentDate());
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

        PaymentDateWorkbookWriter.Result result = PaymentDateWorkbookWriter.applyUpdates(bytes, "", List.of(customer));
        assertEquals(1, result.updatedRows());
        assertTrue(result.notFoundCustomers().isEmpty());

        PaymentDateWorkbookParseResult parsed = PaymentDateWorkbookParser.parse(result.bytes(), "");
        assertEquals(1, parsed.rows().size());
        assertEquals("Paid half", parsed.rows().getFirst().note());
    }
}
