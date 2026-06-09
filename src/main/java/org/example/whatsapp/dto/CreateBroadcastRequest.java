package org.example.whatsapp.dto;

import java.util.List;

public record CreateBroadcastRequest(String messageTemplate, List<BroadcastRecipientInput> recipients) {
}
