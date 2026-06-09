package org.example.model;

import java.time.Instant;

public record DashboardUserRow(
        String displayName,
        String username,
        boolean admin,
        boolean active,
        Instant updatedAt
) {
}
