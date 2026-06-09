package org.example.model;

import java.time.Instant;
import java.util.List;

public record DashboardUploadJobInfo(
        boolean busy,
        String state,
        String phase,
        String message,
        String startedBy,
        Instant startedAt,
        Instant completedAt,
        List<String> filenames
) {
}
