package org.example.drive;

import java.time.Instant;
import java.util.List;

public record DrivePaymentDateSyncResponse(
        boolean enabled,
        boolean configured,
        boolean running,
        Instant lastStartedAt,
        Instant lastFinishedAt,
        String lastStatus,
        String lastMessage,
        String lastFileName,
        int rowsRead,
        int updated,
        int unchanged,
        int unmatched,
        int invalidDates,
        int ambiguous,
        List<DriveSyncRowIssue> unmatchedRows,
        List<DriveSyncRowIssue> invalidDateRows
) {
}
