package org.example.model;

import java.time.Instant;

public record DashboardFileStatus(
        boolean present,
        String filename,
        Instant uploadedAt,
        int rowCount
) {
    public static DashboardFileStatus absent() {
        return new DashboardFileStatus(false, null, null, 0);
    }
}
