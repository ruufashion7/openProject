package org.example.drive;

public record PaymentDateWorkbookRow(
        int excelRow,
        String customerKey,
        String customerName,
        String phoneNumber,
        String nextPaymentDate,
        String note
) {
}
