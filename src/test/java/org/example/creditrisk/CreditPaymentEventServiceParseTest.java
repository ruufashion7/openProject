package org.example.creditrisk;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CreditPaymentEventServiceParseTest {

    @Test
    void parsesCustomerLedgerWorkbook() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = wb.createSheet("Sheet1");
            sheet.createRow(0).createCell(0).setCellValue("Customer Ledger");
            var header = sheet.createRow(1);
            header.createCell(0).setCellValue("Customer");
            header.createCell(1).setCellValue("Transaction Type");
            header.createCell(2).setCellValue("Voucher Date");
            header.createCell(3).setCellValue("Amount");
            var data = sheet.createRow(2);
            data.createCell(0).setCellValue("shop (999)");
            data.createCell(1).setCellValue("Invoice Payment");
            data.createCell(2).setCellValue("21-Aug-2026");
            data.createCell(3).setCellValue(5000);
            wb.write(out);
            bytes = out.toByteArray();
        }
        CreditPaymentEventUploadRepository repo = mock(CreditPaymentEventUploadRepository.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        CreditPaymentEventService service = new CreditPaymentEventService(repo);
        MockMultipartFile file = new MockMultipartFile("file", "CustomerLedger.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);
        CreditPaymentEventUpload saved = service.store(file, "test");
        assertNotNull(saved);
        assertTrue(saved.dataRowCount() != null && saved.dataRowCount() >= 1);
        assertTrue(CustomerLedgerExcelUploadValidation.FORMAT_CUSTOMER_LEDGER.equals(saved.format()));
    }

    @Test
    void parsesUserDesktopSampleIfPresent() throws Exception {
        Path sample = Path.of("/Users/akash.khandelia/Desktop/CustomerLedger.xlsx");
        if (!Files.isRegularFile(sample)) {
            return;
        }
        CreditPaymentEventUploadRepository repo = mock(CreditPaymentEventUploadRepository.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        CreditPaymentEventService service = new CreditPaymentEventService(repo);
        byte[] bytes = Files.readAllBytes(sample);
        MockMultipartFile file = new MockMultipartFile("file", "CustomerLedger.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);
        CreditPaymentEventUpload saved = service.store(file, "test");
        assertTrue(saved.dataRowCount() != null && saved.dataRowCount() > 100);
    }
}
