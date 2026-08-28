package org.example.drive;

import org.example.customer.CustomerIdentity;
import org.example.payment.PaymentDateOverride;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Maps Excel customer rows onto existing {@code customer_master} records by Customer ID, then name.
 */
public final class DriveCustomerMatcher {

    private static final double AMBIGUOUS_DELTA = 0.05;

    private DriveCustomerMatcher() {
    }

    public static Optional<PaymentDateOverride> match(
            PaymentDateWorkbookRow row,
            Map<String, PaymentDateOverride> byKey
    ) {
        Optional<PaymentDateOverride> byId = matchByCustomerKey(row.customerKey(), byKey);
        if (byId.isPresent()) {
            return byId;
        }
        String key = CustomerIdentity.normalizeKey(row.customerName());
        PaymentDateOverride exact = byKey.get(key);
        if (exact != null) {
            return Optional.of(exact);
        }
        return bestNameAmong(row.customerName(), new ArrayList<>(byKey.values()));
    }

    public static boolean isAmbiguous(
            PaymentDateWorkbookRow row,
            Map<String, PaymentDateOverride> byKey
    ) {
        if (row.customerKey() != null && !row.customerKey().isBlank()) {
            String key = CustomerIdentity.normalizeKey(row.customerKey());
            if (byKey.containsKey(key)) {
                return false;
            }
        }
        String key = CustomerIdentity.normalizeKey(row.customerName());
        if (byKey.containsKey(key)) {
            return false;
        }
        return countCloseNameMatches(row.customerName(), new ArrayList<>(byKey.values())) > 1;
    }

    public static Map<String, PaymentDateOverride> indexByKey(Iterable<PaymentDateOverride> overrides) {
        Map<String, PaymentDateOverride> byKey = new HashMap<>();
        for (PaymentDateOverride override : overrides) {
            if (override.customerKey() == null || override.customerKey().isBlank()) {
                continue;
            }
            byKey.putIfAbsent(override.customerKey(), override);
        }
        return byKey;
    }

    private static Optional<PaymentDateOverride> matchByCustomerKey(String rawKey, Map<String, PaymentDateOverride> byKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return Optional.empty();
        }
        String key = CustomerIdentity.normalizeKey(rawKey);
        if (key.isBlank()) {
            return Optional.empty();
        }
        PaymentDateOverride exact = byKey.get(key);
        return exact == null ? Optional.empty() : Optional.of(exact);
    }

    private static Optional<PaymentDateOverride> bestNameAmong(String displayName, List<PaymentDateOverride> candidates) {
        PaymentDateOverride best = null;
        double bestScore = 0;
        double second = 0;
        for (PaymentDateOverride candidate : candidates) {
            double score = CustomerIdentity.similarity(displayName, candidate.customerName());
            if (score > bestScore) {
                second = bestScore;
                bestScore = score;
                best = candidate;
            } else if (score > second) {
                second = score;
            }
        }
        if (best == null || bestScore < CustomerIdentity.FUZZY_MATCH_THRESHOLD) {
            return Optional.empty();
        }
        if (second >= CustomerIdentity.FUZZY_MATCH_THRESHOLD && (bestScore - second) < AMBIGUOUS_DELTA) {
            return Optional.empty();
        }
        return Optional.of(best);
    }

    private static int countCloseNameMatches(String displayName, List<PaymentDateOverride> candidates) {
        int count = 0;
        double best = 0;
        for (PaymentDateOverride candidate : candidates) {
            double score = CustomerIdentity.similarity(displayName, candidate.customerName());
            if (score >= CustomerIdentity.FUZZY_MATCH_THRESHOLD) {
                count++;
                best = Math.max(best, score);
            }
        }
        if (count <= 1) {
            return count;
        }
        int close = 0;
        for (PaymentDateOverride candidate : candidates) {
            double score = CustomerIdentity.similarity(displayName, candidate.customerName());
            if (score >= CustomerIdentity.FUZZY_MATCH_THRESHOLD && (best - score) < AMBIGUOUS_DELTA) {
                close++;
            }
        }
        return close;
    }
}
