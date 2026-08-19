package org.example.drive;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Document(collection = "drive_payment_date_sync")
public record DrivePaymentDateSyncState(
        @Id String id,
        Instant lastStartedAt,
        Instant lastFinishedAt,
        String lastStatus,
        String lastMessage,
        String lastFileName,
        String lastChecksum,
        Integer rowsRead,
        Integer updated,
        Integer unchanged,
        Integer unmatched,
        Integer invalidDates,
        Integer ambiguous,
        List<DriveSyncRowIssue> unmatchedRows,
        List<DriveSyncRowIssue> invalidDateRows,
        boolean running
) {
    public static final String SINGLETON_ID = "current";

    public static DrivePaymentDateSyncState idle() {
        return new DrivePaymentDateSyncState(
                SINGLETON_ID,
                null,
                null,
                "idle",
                "Drive Excel sync has not run yet.",
                null,
                null,
                0,
                0,
                0,
                0,
                0,
                0,
                List.of(),
                List.of(),
                false
        );
    }
}
