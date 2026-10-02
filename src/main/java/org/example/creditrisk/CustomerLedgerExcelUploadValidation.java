package org.example.creditrisk;

import org.example.upload.ExcelUploadHeaderRules;
import org.example.upload.UploadedExcelFile;
import org.example.upload.UploadedExcelSheet;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Validates Credit Risk payments upload — Customer Ledger .xlsx only. */
public final class CustomerLedgerExcelUploadValidation {

    public static final String FORMAT_CUSTOMER_LEDGER = "CUSTOMER_LEDGER";

    private CustomerLedgerExcelUploadValidation() {
    }

    public static void validateParsedOrThrow(UploadedExcelFile file) {
        if (file == null || file.sheets() == null || file.sheets().isEmpty()) {
            throw new IllegalArgumentException("Workbook has no sheets.");
        }
        boolean anyLedger = false;
        int dataRows = 0;
        int validRows = 0;
        for (UploadedExcelSheet sheet : file.sheets()) {
            if (sheet.headers() == null || sheet.headers().isEmpty()) {
                continue;
            }
            if (sheet.headers().stream().anyMatch(ExcelUploadHeaderRules::isPaymentStatusHeader)) {
                throw new IllegalArgumentException(
                        "Wrong template. Upload Customer Ledger export only (Customer, Transaction Type, Voucher Date, …). "
                                + "Payment Status column files are not accepted."
                );
            }
            if (!ExcelUploadHeaderRules.isCustomerLedgerHeaderRow(sheet.headers())) {
                continue;
            }
            anyLedger = true;
            List<String> customerHeaders = sheet.headers().stream()
                    .filter(h -> h != null && h.trim().equalsIgnoreCase("Customer"))
                    .toList();
            List<String> txnHeaders = sheet.headers().stream()
                    .filter(ExcelUploadHeaderRules::isTransactionTypeHeader)
                    .toList();
            if (customerHeaders.isEmpty() || txnHeaders.isEmpty()) {
                throw new IllegalArgumentException("Customer Ledger sheet is missing Customer or Transaction Type column.");
            }
            for (Map<String, String> row : sheet.rows()) {
                dataRows++;
                String customer = CreditRiskParseSupport.firstNonBlank(row, customerHeaders);
                String txn = CreditRiskParseSupport.firstNonBlank(row, txnHeaders);
                if (customer != null && txn != null && !txn.isBlank()) {
                    validRows++;
                }
            }
        }
        if (!anyLedger) {
            throw new IllegalArgumentException(
                    "Not a Customer Ledger file. Expected header row: Customer, Transaction Type, Voucher Date, "
                            + "Voucher No., Accounting Date, Amount, … (same as CustomerLedger.xlsx export)."
            );
        }
        if (validRows == 0) {
            throw new IllegalArgumentException("Customer Ledger has no data rows with Customer and Transaction Type.");
        }
    }

    public static int countValidDataRows(UploadedExcelFile file) {
        int valid = 0;
        for (UploadedExcelSheet sheet : file.sheets()) {
            if (!ExcelUploadHeaderRules.isCustomerLedgerHeaderRow(sheet.headers())) {
                continue;
            }
            List<String> customerHeaders = sheet.headers().stream()
                    .filter(h -> h != null && h.trim().equalsIgnoreCase("Customer"))
                    .toList();
            List<String> txnHeaders = sheet.headers().stream()
                    .filter(ExcelUploadHeaderRules::isTransactionTypeHeader)
                    .toList();
            for (Map<String, String> row : sheet.rows()) {
                String customer = CreditRiskParseSupport.firstNonBlank(row, customerHeaders);
                String txn = CreditRiskParseSupport.firstNonBlank(row, txnHeaders);
                if (customer != null && txn != null && !txn.isBlank()) {
                    valid++;
                }
            }
        }
        return valid;
    }

    static boolean isFailureTransactionType(String transactionType) {
        if (transactionType == null || transactionType.isBlank()) {
            return false;
        }
        String n = transactionType.toLowerCase(Locale.ROOT);
        return n.contains("fail") || n.contains("bounce") || n.contains("return") || n.contains("dishonour")
                || n.contains("dishonor") || n.contains("reject");
    }
}
