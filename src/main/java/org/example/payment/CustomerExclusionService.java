package org.example.payment;

import org.example.customer.CustomerIdentity;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * User-controlled customer exclusion — hidden from Outstanding Due and outreach pickers.
 * Separate from {@link PaymentDateOverride#active()} (upload presence sync).
 */
@Service
public class CustomerExclusionService {

    private final PaymentDateOverrideRepository repository;

    public CustomerExclusionService(PaymentDateOverrideRepository repository) {
        this.repository = repository;
    }

    public boolean isExcluded(String customerKey) {
        if (customerKey == null || customerKey.isBlank()) {
            return false;
        }
        return repository.findFirstByCustomerKeyOrderByIdAsc(customerKey)
                .map(PaymentDateOverride::isExcluded)
                .orElse(false);
    }

    public List<ExcludedCustomerView> listExcluded() {
        return repository.findAll().stream()
                .filter(PaymentDateOverride::isExcluded)
                .sorted((a, b) -> {
                    String na = a.customerName() != null ? a.customerName() : a.customerKey();
                    String nb = b.customerName() != null ? b.customerName() : b.customerKey();
                    return na.compareToIgnoreCase(nb);
                })
                .map(o -> new ExcludedCustomerView(
                        o.customerKey(),
                        o.customerName(),
                        o.excludedAt(),
                        o.excludedBy()
                ))
                .toList();
    }

    public ExcludedCustomerView excludeByDisplayName(String displayName, String performedBy) {
        String key = CustomerIdentity.normalizeKey(displayName);
        if (key.isBlank()) {
            throw new IllegalArgumentException("Customer name is required");
        }
        PaymentDateOverride saved = repository.findFirstByCustomerKeyOrderByIdAsc(key)
                .map(existing -> repository.save(PaymentDateOverrideCopy.withExcluded(existing, true, performedBy)))
                .orElseGet(() -> repository.save(
                        PaymentDateOverrideCopy.withExcluded(
                                PaymentDateOverrideCopy.newShell(key, displayName.trim()),
                                true,
                                performedBy
                        )
                ));
        return toView(saved);
    }

    public ExcludedCustomerView restoreByCustomerKey(String customerKey, String performedBy) {
        if (customerKey == null || customerKey.isBlank()) {
            throw new IllegalArgumentException("Customer key is required");
        }
        PaymentDateOverride existing = repository.findFirstByCustomerKeyOrderByIdAsc(customerKey)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found"));
        if (!existing.isExcluded()) {
            throw new IllegalStateException("Customer is not ignored");
        }
        PaymentDateOverride saved = repository.save(PaymentDateOverrideCopy.withExcluded(existing, false, performedBy));
        return toView(saved);
    }

    private static ExcludedCustomerView toView(PaymentDateOverride o) {
        return new ExcludedCustomerView(o.customerKey(), o.customerName(), o.excludedAt(), o.excludedBy());
    }

    public record ExcludedCustomerView(
            String customerKey,
            String customerName,
            Instant excludedAt,
            String excludedBy
    ) {
    }
}
