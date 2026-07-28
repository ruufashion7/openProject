package org.example.payment;

import org.example.customer.CustomerIdentity;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Shared team list of retained customers — always shown on Outstanding Due (including ₹0).
 * Mutually exclusive with {@link CustomerExclusionService} ignore.
 */
@Service
public class CustomerRetentionService {

    private final PaymentDateOverrideRepository repository;

    public CustomerRetentionService(PaymentDateOverrideRepository repository) {
        this.repository = repository;
    }

    public List<RetainedCustomerView> listRetained() {
        return repository.findAll().stream()
                .filter(PaymentDateOverride::isRetained)
                .sorted((a, b) -> {
                    String na = a.customerName() != null ? a.customerName() : a.customerKey();
                    String nb = b.customerName() != null ? b.customerName() : b.customerKey();
                    return na.compareToIgnoreCase(nb);
                })
                .map(this::toView)
                .toList();
    }

    public RetainedCustomerView retainByDisplayName(String displayName, String performedBy) {
        String trimmed = displayName.trim();
        String key = CustomerIdentity.normalizeKey(trimmed);
        if (key.isBlank()) {
            throw new IllegalArgumentException("Customer name is required");
        }

        PaymentDateOverride target = findExistingForDisplayName(trimmed)
                .orElseGet(() -> PaymentDateOverrideCopy.newShell(key, trimmed));

        if (target.isExcluded()) {
            throw new IllegalStateException(
                    "Customer is ignored. Restore them from Ignored customers before retaining."
            );
        }
        if (target.isRetained()) {
            return toView(target);
        }

        PaymentDateOverride saved = repository.save(
                PaymentDateOverrideCopy.withRetained(target, true, performedBy)
        );
        return toView(saved);
    }

    public RetainedCustomerView unretainByCustomerKey(String customerKey, String performedBy) {
        if (customerKey == null || customerKey.isBlank()) {
            throw new IllegalArgumentException("Customer key is required");
        }
        PaymentDateOverride existing = repository.findFirstByCustomerKeyOrderByIdAsc(customerKey)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found"));
        if (!existing.isRetained()) {
            throw new IllegalStateException("Customer is not retained");
        }
        PaymentDateOverride saved = repository.save(
                PaymentDateOverrideCopy.withRetained(existing, false, performedBy)
        );
        return toView(saved);
    }

    private Optional<PaymentDateOverride> findExistingForDisplayName(String displayName) {
        String key = CustomerIdentity.normalizeKey(displayName);
        Optional<PaymentDateOverride> byKey = repository.findFirstByCustomerKeyOrderByIdAsc(key);
        if (byKey.isPresent()) {
            return byKey;
        }

        PaymentDateOverride best = null;
        double bestSimilarity = 0.0;
        for (PaymentDateOverride candidate : repository.findAll()) {
            double similarity = CustomerIdentity.similarity(displayName, candidate.customerName());
            if (similarity >= CustomerIdentity.FUZZY_MATCH_THRESHOLD && similarity > bestSimilarity) {
                bestSimilarity = similarity;
                best = candidate;
            }
        }
        return Optional.ofNullable(best);
    }

    private RetainedCustomerView toView(PaymentDateOverride o) {
        return new RetainedCustomerView(o.customerKey(), o.customerName(), o.retainedAt(), o.retainedBy());
    }

    public record RetainedCustomerView(
            String customerKey,
            String customerName,
            Instant retainedAt,
            String retainedBy
    ) {
    }
}
