package org.example.whatsapp;

import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Component
public class WaMeLinkDispatchExecutor implements WhatsAppDispatchExecutor {

    private static final int MAX_MESSAGE_UTF8_LENGTH = 3500;

    @Override
    public WhatsAppChannel channel() {
        return WhatsAppChannel.WAME_LINK;
    }

    @Override
    public String buildWaMeUrl(String phoneDigits, String messagePlainText) {
        if (phoneDigits == null || phoneDigits.isBlank()) {
            throw new IllegalArgumentException("Phone number is required");
        }
        String digits = phoneDigits.replaceAll("\\D", "");
        if (digits.isEmpty()) {
            throw new IllegalArgumentException("Phone number has no digits");
        }
        String base = "https://wa.me/" + digits;
        if (messagePlainText == null || messagePlainText.isBlank()) {
            return base;
        }
        String text = messagePlainText;
        if (text.length() > maxMessageLength()) {
            text = text.substring(0, maxMessageLength());
        }
        String encoded = URLEncoder.encode(text, StandardCharsets.UTF_8);
        return base + "?text=" + encoded;
    }

    @Override
    public int maxMessageLength() {
        return MAX_MESSAGE_UTF8_LENGTH;
    }
}
