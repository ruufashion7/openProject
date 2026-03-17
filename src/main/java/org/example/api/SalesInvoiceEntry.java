package org.example.api;

public record SalesInvoiceEntry(
        String invoiceDate,
        String voucherNo,
        String customer,
        String customerPhone,
        double receivedAmount,
        double currentDue,
        Integer ageingDays
) {
}

