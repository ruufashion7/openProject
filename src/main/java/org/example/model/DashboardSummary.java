package org.example.model;

import java.time.Instant;
import java.util.List;

/** Live operations snapshot for the admin dashboard (Mongo + upload state). */
public record DashboardSummary(
        Instant generatedAt,
        boolean analyticsReady,
        DashboardSystemInfo system,
        DashboardUploadJobInfo uploadJob,
        DashboardCustomerStats customers,
        DashboardWhatsappStats whatsapp,
        int activeUsers,
        int inactiveUsers,
        int adminUsers,
        int activeSessions,
        long rateListEntries,
        long uploadAuditEntries,
        DashboardFileStatus detailedFile,
        DashboardFileStatus receivableFile,
        List<DashboardUserRow> users,
        List<DashboardActivityItem> recentUploadActivity,
        List<String> alerts
) {
}
