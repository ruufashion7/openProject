package org.example.model;

public record DashboardSystemInfo(
        String applicationName,
        String version,
        String activeProfiles,
        String rateLimitBackend,
        boolean redisRateLimitActive
) {
}
