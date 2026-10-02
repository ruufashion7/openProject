package org.example.creditrisk;

import org.example.upload.UploadedExcelFile;
import org.example.upload.UploadedExcelSheet;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomerLedgerExcelUploadValidationTest {

    @Test
    void rejectsPaymentStatusTemplate() {
        UploadedExcelFile file = new UploadedExcelFile(
                "bad.xlsx",
                List.of(new UploadedExcelSheet(
                        "Sheet1",
                        List.of("Customer", "Payment Status", "Payment Date"),
                        List.of(Map.of("Customer", "A", "Payment Status", "failed", "Payment Date", "01-Jan-2026"))
                ))
        );
        assertThrows(IllegalArgumentException.class, () -> CustomerLedgerExcelUploadValidation.validateParsedOrThrow(file));
    }

    @Test
    void acceptsMinimalCustomerLedgerHeaders() {
        UploadedExcelFile file = new UploadedExcelFile(
                "ok.xlsx",
                List.of(new UploadedExcelSheet(
                        "Sheet1",
                        List.of("Customer", "Transaction Type", "Voucher Date", "Amount"),
                        List.of(Map.of(
                                "Customer", "test (999)",
                                "Transaction Type", "Invoice Payment",
                                "Voucher Date", "21-Aug-2026",
                                "Amount", "1000"
                        ))
                ))
        );
        assertDoesNotThrow(() -> CustomerLedgerExcelUploadValidation.validateParsedOrThrow(file));
        assertTrue(CustomerLedgerExcelUploadValidation.countValidDataRows(file) >= 1);
    }

    @Test
    void failureTxnDetection() {
        assertTrue(CustomerLedgerExcelUploadValidation.isFailureTransactionType("Cheque Bounce"));
        assertTrue(!CustomerLedgerExcelUploadValidation.isFailureTransactionType("Invoice Payment"));
    }
}
