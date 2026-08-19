package org.example.drive;

import java.util.List;

public record PaymentDateWorkbookParseResult(
        String sheetName,
        List<PaymentDateWorkbookRow> rows,
        List<DriveSyncRowIssue> invalidDates
) {
}
