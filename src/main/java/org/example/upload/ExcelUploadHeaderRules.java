package org.example.upload;

import java.util.List;
import java.util.Locale;

/**
 * Expected sheet/column shape for sales and receivable exports (after a workbook is parsed).
 * Multipart/filename limits are in {@link SalesReceivableExcelUploadValidation}.
 * Used by {@link org.example.api.AnalyticsController} and {@link UploadStorageService} so wrong templates
 * are rejected before replacing stored data.
 */
public final class ExcelUploadHeaderRules {

    private ExcelUploadHeaderRules() {
    }

    public static boolean isCustomerHeader(String header) {
        if (header == null) {
            return false;
        }
        String normalized = header.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("customer");
    }

    public static boolean isDriveOutstandingAmountHeader(String header) {
        if (header == null) {
            return false;
        }
        String normalized = header.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("date") || normalized.contains("phone") || normalized.contains("note")) {
            return false;
        }
        return normalized.equals("outstanding amount")
                || normalized.equals("due amount")
                || normalized.equals("amount due")
                || normalized.equals("outstanding")
                || normalized.equals("due");
    }

    public static boolean isCustomerIdHeader(String header) {
        if (header == null) {
            return false;
        }
        String normalized = header.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("customer id")
                || normalized.equals("customer key")
                || normalized.equals("cust id")
                || normalized.equals("customer code");
    }

    public static boolean isPhoneHeader(String header) {
        if (header == null) {
            return false;
        }
        String normalized = header.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("mobile")
                || normalized.contains("phone")
                || normalized.contains("contact");
    }

    public static boolean isCustomerPhoneHeader(String header) {
        if (header == null) {
            return false;
        }
        String normalized = header.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("customer phone")) {
            return true;
        }
        return normalized.contains("customer") && isPhoneHeader(header);
    }

    public static boolean isAmountHeader(String header) {
        if (header == null) {
            return false;
        }
        String normalized = header.trim().toLowerCase(Locale.ROOT);
        boolean hasDigits = normalized.matches(".*\\d+.*");
        return normalized.contains("amount")
                || normalized.contains("outstanding")
                || normalized.contains("balance")
                || normalized.contains("total")
                || (hasDigits && (normalized.contains("days") || normalized.contains("ageing") || normalized.contains("aging")));
    }

    public static boolean isInvoiceDateHeader(String header) {
        if (header == null) {
            return false;
        }
        String normalized = header.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("invoice date")
                || (normalized.contains("invoice") && normalized.contains("date"));
    }

    public static boolean isVoucherHeader(String header) {
        if (header == null) {
            return false;
        }
        String normalized = header.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("voucher no.")
                || normalized.equals("voucher no")
                || normalized.contains("voucher")
                || normalized.contains("invoice no")
                || normalized.contains("inv no")
                || normalized.contains("bill no");
    }

    public static boolean isReceivedHeader(String header) {
        if (header == null) {
            return false;
        }
        String normalized = header.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("date")) {
            return false;
        }
        return normalized.equals("received amount")
                || normalized.contains("received amount")
                || normalized.contains("received")
                || normalized.contains("receipt")
                || normalized.contains("rcvd")
                || normalized.contains("rcv")
                || normalized.contains("paid")
                || normalized.contains("payment")
                || normalized.contains("collection");
    }

    public static boolean isCurrentDueHeader(String header) {
        if (header == null) {
            return false;
        }
        String normalized = header.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("date") || normalized.contains("days") || normalized.contains("ageing") || normalized.contains("aging")) {
            return false;
        }
        return normalized.equals("current due")
                || normalized.contains("current due")
                || normalized.contains("current outstanding")
                || normalized.contains("current")
                || normalized.contains("due amount")
                || normalized.contains("outstanding")
                || normalized.contains("balance")
                || normalized.contains("balance amt")
                || normalized.contains("pending")
                || normalized.equals("due");
    }

    /** Invoice due date column (credit-risk engine). */
    public static boolean isDueDateHeader(String header) {
        if (header == null) {
            return false;
        }
        String normalized = header.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("due date")
                || normalized.equals("invoice due date")
                || (normalized.contains("due") && normalized.contains("date") && !normalized.contains("payment"));
    }

    /** Payment terms in days. */
    public static boolean isPaymentTermsHeader(String header) {
        if (header == null) {
            return false;
        }
        String normalized = header.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("payment term")
                || normalized.equals("credit days")
                || normalized.equals("due days")
                || normalized.equals("terms days");
    }

    public static boolean isPaymentStatusHeader(String header) {
        if (header == null) {
            return false;
        }
        String normalized = header.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("payment status")
                || normalized.equals("txn status")
                || normalized.equals("transaction status");
    }

    /** Customer Ledger export (Credit Risk payments upload). */
    public static boolean isTransactionTypeHeader(String header) {
        if (header == null) {
            return false;
        }
        return header.trim().equalsIgnoreCase("Transaction Type");
    }

    public static boolean isVoucherDateHeader(String header) {
        if (header == null) {
            return false;
        }
        return header.trim().equalsIgnoreCase("Voucher Date");
    }

    public static boolean isAccountingDateHeader(String header) {
        if (header == null) {
            return false;
        }
        return header.trim().equalsIgnoreCase("Accounting Date");
    }

    public static boolean isLedgerCreditHeader(String header) {
        if (header == null) {
            return false;
        }
        return header.trim().equalsIgnoreCase("Credit");
    }

    public static boolean isLedgerDebitHeader(String header) {
        if (header == null) {
            return false;
        }
        return header.trim().equalsIgnoreCase("Debit");
    }

    /**
     * Header row shape for Customer Ledger.xlsx (title row optional above).
     */
    public static boolean isCustomerLedgerHeaderRow(List<String> headers) {
        if (headers == null || headers.isEmpty()) {
            return false;
        }
        boolean customer = headers.stream().anyMatch(h -> h != null && h.trim().equalsIgnoreCase("Customer"));
        boolean txn = headers.stream().anyMatch(ExcelUploadHeaderRules::isTransactionTypeHeader);
        boolean date = headers.stream().anyMatch(h ->
                isVoucherDateHeader(h) || isAccountingDateHeader(h));
        boolean amount = headers.stream().anyMatch(h ->
                h != null && h.trim().equalsIgnoreCase("Amount"));
        return customer && txn && date && amount;
    }

    public static boolean isInvoiceStatusHeader(String header) {
        if (header == null) {
            return false;
        }
        String normalized = header.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("status")
                || normalized.equals("invoice status")
                || normalized.equals("voucher status");
    }

    public static boolean isCreditNoteHeader(String header) {
        if (header == null) {
            return false;
        }
        String normalized = header.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("credit note") || normalized.equals("cn amount");
    }

    public static boolean isMarginHeader(String header) {
        if (header == null) {
            return false;
        }
        String normalized = header.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("margin") || normalized.contains("gross profit") || normalized.equals("gp%");
    }

    /** Payment event date column (Customer Ledger: Voucher / Accounting Date). */
    public static boolean isPaymentDateHeader(String header) {
        if (header == null) {
            return false;
        }
        if (isVoucherDateHeader(header) || isAccountingDateHeader(header)) {
            return true;
        }
        String normalized = header.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("payment date")
                || normalized.equals("txn date")
                || normalized.equals("transaction date")
                || normalized.equals("cheque date");
    }

    public static boolean isPaymentAmountHeader(String header) {
        if (header == null) {
            return false;
        }
        String normalized = header.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("payment amount")
                || normalized.equals("paid amount")
                || normalized.equals("amount paid")
                || (normalized.contains("payment") && normalized.contains("amount"));
    }

    /**
     * True if at least one sheet looks like Detailed Sales Invoices (customer + identifiers + amounts).
     */
    public static boolean matchesDetailedSalesTemplate(UploadedExcelFile file) {
        for (UploadedExcelSheet sheet : file.sheets()) {
            if (sheetMatchesDetailedSales(sheet)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True if at least one sheet looks like Receivable Ageing (customer + amount columns).
     */
    public static boolean matchesReceivableAgeingTemplate(UploadedExcelFile file) {
        for (UploadedExcelSheet sheet : file.sheets()) {
            if (sheetMatchesReceivableAgeing(sheet)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sheetMatchesDetailedSales(UploadedExcelSheet sheet) {
        List<String> headers = sheet.headers();
        boolean customer = headers.stream().anyMatch(ExcelUploadHeaderRules::isCustomerHeader);
        boolean invoiceOrVoucher = headers.stream().anyMatch(h -> isInvoiceDateHeader(h) || isVoucherHeader(h));
        boolean amounts = headers.stream().anyMatch(h -> isReceivedHeader(h) || isCurrentDueHeader(h));
        return customer && invoiceOrVoucher && amounts;
    }

    private static boolean sheetMatchesReceivableAgeing(UploadedExcelSheet sheet) {
        List<String> headers = sheet.headers();
        boolean customer = headers.stream().anyMatch(ExcelUploadHeaderRules::isCustomerHeader);
        boolean amount = headers.stream().anyMatch(ExcelUploadHeaderRules::isAmountHeader);
        return customer && amount;
    }

    /**
     * Ensures file1 is Detailed Sales and file2 is Receivable Ageing; detects swapped uploads.
     */
    public static void validateUploadPairOrThrow(UploadedExcelFile detailedSlot, UploadedExcelFile receivableSlot) {
        boolean okDefault = matchesDetailedSalesTemplate(detailedSlot) && matchesReceivableAgeingTemplate(receivableSlot);
        if (okDefault) {
            return;
        }
        boolean swapped = matchesDetailedSalesTemplate(receivableSlot) && matchesReceivableAgeingTemplate(detailedSlot);
        if (swapped) {
            throw new IllegalArgumentException(
                    "The two files appear to be swapped. Put Detailed Sales Invoices in the first field and Receivable Ageing Report in the second.");
        }
        if (!matchesDetailedSalesTemplate(detailedSlot)) {
            if (matchesReceivableAgeingTemplate(detailedSlot) && !matchesReceivableAgeingTemplate(receivableSlot)) {
                throw new IllegalArgumentException(
                        "File 1 looks like a Receivable Ageing Report, not Detailed Sales Invoices. Swap the two files if both exports are correct.");
            }
            throw new IllegalArgumentException(
                    "File 1 (Detailed Sales Invoices) does not match the expected export: need columns for Customer, Invoice date or Voucher, and Received or Current due.");
        }
        if (!matchesReceivableAgeingTemplate(receivableSlot)) {
            if (matchesDetailedSalesTemplate(receivableSlot)) {
                throw new IllegalArgumentException(
                        "File 2 looks like Detailed Sales Invoices, not a Receivable Ageing Report. Put the ageing report in the second field (swap if both files are correct).");
            }
            throw new IllegalArgumentException(
                    "File 2 (Receivable Ageing Report) does not match the expected export: need columns for Customer and amount totals (e.g. ageing buckets).");
        }
    }
}
