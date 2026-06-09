package org.example.model;

import java.time.Instant;

public record DashboardActivityItem(
        String action,
        String type,
        String filename,
        Instant uploadedAt
) {
}
