package org.example.drive;

public record DriveSyncRowIssue(
        int excelRow,
        String customerName,
        String reason
) {
}
