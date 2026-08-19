package org.example.drive;

public record PaymentDateWorkbookRow(
        int excelRow,
        String customerName,
        String phone,
        String nextPaymentDate
) {
}
