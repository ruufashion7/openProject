package org.example.customer;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Canonical customer key used across analytics, customer_master, and uploads (spaces between tokens).
 * Matches {@code AnalyticsController#normalizeCustomer} / notes flows — not the underscore variant used in WhatsApp UI.
 */
public final class CustomerIdentity {

    public static final double FUZZY_MATCH_THRESHOLD = 0.7;

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

    /** Token Jaccard similarity in {@code [0, 1]} — same rules as Outstanding Due fuzzy matching. */
    public static double similarity(String name1, String name2) {
        if (name1 == null || name2 == null || name1.isBlank() || name2.isBlank()) {
            return 0.0;
        }

        String normalized1 = normalizeKey(name1);
        String normalized2 = normalizeKey(name2);
        if (normalized1.equals(normalized2)) {
            return 1.0;
        }

        List<String> tokens1 = tokenize(normalized1);
        List<String> tokens2 = tokenize(normalized2);
        if (tokens1.isEmpty() || tokens2.isEmpty()) {
            return 0.0;
        }

        Set<String> set1 = new HashSet<>(tokens1);
        Set<String> set2 = new HashSet<>(tokens2);

        Set<String> intersection = new HashSet<>(set1);
        intersection.retainAll(set2);

        Set<String> union = new HashSet<>(set1);
        union.addAll(set2);
        if (union.isEmpty()) {
            return 0.0;
        }

        double jaccard = (double) intersection.size() / union.size();
        if (set1.containsAll(set2) || set2.containsAll(set1)) {
            jaccard = Math.max(jaccard, 0.8);
        }
        return jaccard;
    }

    public static boolean matchesFuzzy(String displayName, String candidateName) {
        return similarity(displayName, candidateName) >= FUZZY_MATCH_THRESHOLD;
    }

    private static List<String> tokenize(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value.split("\\s+"));
    }
}
