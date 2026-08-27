package org.example.drive;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentDateWorkbookParserTest {

    @Test
    void parse_customerAndDateColumns() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("PaymentDates");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Customer Name");
            header.createCell(1).setCellValue("Phone Number");
            header.createCell(2).setCellValue("Next Payment Date");
            header.createCell(3).setCellValue("Notes");

            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("ABC Traders");
            r1.createCell(1).setCellValue("9876543210");
            r1.createCell(2).setCellValue("18-08");
            r1.createCell(3).setCellValue("Call Monday");

            Row r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue("XYZ Stores");
            r2.createCell(2).setCellValue(LocalDate.of(2026, 3, 5));

            Row r3 = sheet.createRow(3);
            r3.createCell(0).setCellValue("Month Name Co");
            r3.createCell(2).setCellValue("19-Aug-26");

            Row blankDate = sheet.createRow(4);
            blankDate.createCell(0).setCellValue("Skip Me");
            blankDate.createCell(2).setCellValue("");
            blankDate.createCell(3).setCellValue("Follow up");

            Row bad = sheet.createRow(5);
            bad.createCell(0).setCellValue("Bad Date Co");
            bad.createCell(2).setCellValue("99-99");

            workbook.write(out);
            bytes = out.toByteArray();
        }

        PaymentDateWorkbookParseResult result = PaymentDateWorkbookParser.parse(bytes, "");
        assertEquals("PaymentDates", result.sheetName());
        assertEquals(4, result.rows().size());
        assertEquals("ABC Traders", result.rows().get(0).customerName());
        assertEquals("9876543210", result.rows().get(0).phoneNumber());
        assertEquals("18-08", result.rows().get(0).nextPaymentDate());
        assertEquals("Call Monday", result.rows().get(0).note());
        assertEquals("XYZ Stores", result.rows().get(1).customerName());
        assertEquals("05-03", result.rows().get(1).nextPaymentDate());
        assertEquals("Month Name Co", result.rows().get(2).customerName());
        assertEquals("19-08", result.rows().get(2).nextPaymentDate());
        assertEquals("Skip Me", result.rows().get(3).customerName());
        assertEquals("", result.rows().get(3).nextPaymentDate());
        assertEquals("Follow up", result.rows().get(3).note());
        assertEquals(1, result.invalidDates().size());
        assertTrue(result.invalidDates().getFirst().customerName().contains("Bad Date"));
    }

    @Test
    void parse_legacyLayoutWithoutPhoneColumn() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("PaymentDates");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Customer Name");
            header.createCell(1).setCellValue("Next Payment Date");

            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("ABC Traders");
            row.createCell(1).setCellValue("18-08");

            workbook.write(out);
            bytes = out.toByteArray();
        }

        PaymentDateWorkbookParseResult result = PaymentDateWorkbookParser.parse(bytes, "");
        assertEquals(1, result.rows().size());
        assertEquals("", result.rows().getFirst().phoneNumber());
        assertEquals("18-08", result.rows().getFirst().nextPaymentDate());
    }
}
