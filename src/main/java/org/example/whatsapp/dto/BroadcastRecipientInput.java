package org.example.whatsapp.dto;

import java.util.Map;

/**
 * @param placeholders Optional {@code {{token}}} values merged into the message (see WhatsappBroadcastService).
 *                     Keys must be alphanumeric + underscore; values are capped for safety.
 */
public record BroadcastRecipientInput(
        String customerKey, String displayName, String phoneNumber, Map<String, String> placeholders) {

    public BroadcastRecipientInput(String customerKey, String displayName, String phoneNumber) {
        this(customerKey, displayName, phoneNumber, null);
    }
}
