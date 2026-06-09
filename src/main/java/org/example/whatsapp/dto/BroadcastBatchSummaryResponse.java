package org.example.whatsapp.dto;

public record BroadcastBatchSummaryResponse(
        String id,
        String createdAt,
        String channelMode,
        String messagePreview,
        long recipientCount,
        long notSentCount,
        long inProgressCount,
        long sentCount,
        long failedCount
) {
}
