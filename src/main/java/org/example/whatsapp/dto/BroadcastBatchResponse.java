package org.example.whatsapp.dto;

import java.util.List;

public record BroadcastBatchResponse(
        String id,
        String messageTemplate,
        String channelMode,
        String createdAt,
        List<BroadcastRecipientResponse> recipients
) {
}
