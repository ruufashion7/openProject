package org.example.api;

public record CustomerLedgerEntry(
        String invoiceDate,
        String voucherNo,
        double receivedAmount,
        double currentDue,
        Integer ageingDays
) {
}

