package org.example.drive;

import org.example.customer.CustomerIdentity;
import org.example.customer.CustomerPhoneNumbers;
import org.example.payment.PaymentDateOverride;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Maps Excel customer rows onto existing {@code customer_master} records. Never creates customers.
 */
public final class DriveCustomerMatcher {

    private static final double AMBIGUOUS_DELTA = 0.05;

    private DriveCustomerMatcher() {
    }

    public static Optional<PaymentDateOverride> match(
            PaymentDateWorkbookRow row,
            Map<String, PaymentDateOverride> byKey,
            Map<String, List<PaymentDateOverride>> byPhone
    ) {
        String phoneKey = CustomerPhoneNumbers.normalizeDigitsKey(row.phone());
        if (phoneKey != null) {
            List<PaymentDateOverride> phoneHits = byPhone.getOrDefault(phoneKey, List.of());
            if (phoneHits.size() == 1) {
                return Optional.of(phoneHits.getFirst());
            }
            if (phoneHits.size() > 1) {
                Optional<PaymentDateOverride> named = bestNameAmong(row.customerName(), phoneHits);
                if (named.isPresent()) {
                    return named;
                }
                return Optional.empty();
            }
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
            Map<String, PaymentDateOverride> byKey,
            Map<String, List<PaymentDateOverride>> byPhone
    ) {
        String phoneKey = CustomerPhoneNumbers.normalizeDigitsKey(row.phone());
        if (phoneKey != null) {
            List<PaymentDateOverride> phoneHits = byPhone.getOrDefault(phoneKey, List.of());
            if (phoneHits.size() > 1 && bestNameAmong(row.customerName(), phoneHits).isEmpty()) {
                return true;
            }
            if (phoneHits.size() == 1) {
                return false;
            }
        }
        String key = CustomerIdentity.normalizeKey(row.customerName());
        if (byKey.containsKey(key)) {
            return false;
        }
        return countCloseNameMatches(row.customerName(), new ArrayList<>(byKey.values())) > 1;
    }

    public static Map<String, List<PaymentDateOverride>> indexByPhone(Iterable<PaymentDateOverride> overrides) {
        Map<String, List<PaymentDateOverride>> byPhone = new HashMap<>();
        for (PaymentDateOverride override : overrides) {
            String phoneKey = CustomerPhoneNumbers.normalizeDigitsKey(override.phoneNumber());
            if (phoneKey == null) {
                continue;
            }
            byPhone.computeIfAbsent(phoneKey, ignored -> new ArrayList<>()).add(override);
        }
        return byPhone;
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
