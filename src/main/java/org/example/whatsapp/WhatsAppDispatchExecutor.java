package org.example.whatsapp;

/**
 * Strategy for building WhatsApp actions per channel. Cloud API implementation can be added later.
 */
public interface WhatsAppDispatchExecutor {

    WhatsAppChannel channel();

    /**
     * Builds a wa.me URL with optional pre-filled text. phoneDigits: digits only, country code included, no plus.
     */
    String buildWaMeUrl(String phoneDigits, String messagePlainText);

    int maxMessageLength();
}
