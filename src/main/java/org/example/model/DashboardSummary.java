package org.example.model;

import java.time.Instant;
import java.util.List;

public record DashboardSummary(
        int totalUsers,
        int activeSessions,
        double systemLoad,
        List<String> highlights,
        Instant generatedAt
) {
}

