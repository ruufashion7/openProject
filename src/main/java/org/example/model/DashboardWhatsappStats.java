package org.example.model;

import java.time.Instant;

public record DashboardWhatsappStats(
        long batches,
        long recipients,
        long notSent,
        long inProgress,
        long sent,
        long failed,
        Instant latestBatchAt
) {
}
