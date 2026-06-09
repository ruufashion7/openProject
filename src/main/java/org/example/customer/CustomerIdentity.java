package org.example.customer;

import java.util.Locale;

/**
 * Canonical customer key used across analytics, customer_master, and uploads (spaces between tokens).
 * Matches {@code AnalyticsController#normalizeCustomer} / notes flows — not the underscore variant used in WhatsApp UI.
 */
public final class CustomerIdentity {

    private CustomerIdentity() {
    }

    public static String normalizeKey(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
        return normalized;
    }
}
