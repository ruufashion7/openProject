package org.example.whatsapp;

/**
 * Dispatch channel for outreach. WAME_LINK opens WhatsApp via deep link; CLOUD_API reserved for Meta Cloud API.
 */
public enum WhatsAppChannel {
    WAME_LINK,
    CLOUD_API
}
