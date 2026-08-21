package org.example.drive;

public record PaymentDateWorkbookRow(
        int excelRow,
        String customerName,
        String nextPaymentDate,
        String note
) {
}
