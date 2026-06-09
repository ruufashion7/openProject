package org.example.whatsapp.dto;

public record BroadcastRecipientResponse(
        String id,
        String customerKey,
        String displayName,
        String phoneDigits,
        String renderedMessage,
        String status,
        String failureReason,
        String openedAt,
        String sentAt
) {
}
