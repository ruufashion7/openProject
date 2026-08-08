package org.example.settings;

import org.example.payment.PaymentDateOverride;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Service
public class CustomerCreditLimitService {

    public static final String CATEGORY_A = "A";
    public static final String CATEGORY_B = "B";
    public static final String CATEGORY_C = "C";
    public static final String CATEGORY_SEMI_WHOLESALE = "semi-wholesale";

    public static final Set<String> VALID_CATEGORIES = Set.of(
            CATEGORY_A,
            CATEGORY_B,
            CATEGORY_C,
            CATEGORY_SEMI_WHOLESALE
    );

  private static final Map<String, Double> SEED_DEFAULTS = Map.of(
            CATEGORY_A, 50000.0,
            CATEGORY_B, 30000.0,
            CATEGORY_C, 0.0,
            CATEGORY_SEMI_WHOLESALE, 200000.0
    );

    private final CustomerCreditLimitRepository repository;

    public CustomerCreditLimitService(CustomerCreditLimitRepository repository) {
        this.repository = repository;
    }

    public Map<String, Double> getCategoryLimits() {
        CustomerCreditLimitDocument doc = ensureDocument();
        return Collections.unmodifiableMap(normalizeLimits(doc.getLimits()));
    }

    public Map<String, Double> updateCategoryLimits(Map<String, Double> limits, String updatedBy) {
        CustomerCreditLimitDocument doc = ensureDocument();
        Map<String, Double> normalized = normalizeLimits(limits);
        doc.setLimits(normalized);
        doc.setUpdatedAt(Instant.now());
        doc.setUpdatedBy(updatedBy);
        repository.save(doc);
        return Collections.unmodifiableMap(new HashMap<>(normalized));
    }

    public CreditLimitResolution resolve(double totalAmount, PaymentDateOverride override, Map<String, Double> categoryLimits) {
        Double overrideLimit = override != null ? override.creditLimitOverride() : null;
        if (overrideLimit != null) {
            return buildResolution(totalAmount, overrideLimit, overrideLimit, "override");
        }
        String category = override != null ? override.customerCategory() : null;
        if (category != null && categoryLimits.containsKey(category)) {
            return buildResolution(totalAmount, null, categoryLimits.get(category), "category");
        }
        return new CreditLimitResolution(null, null, null, false, null);
    }

    public CreditLimitResolution resolve(double totalAmount, PaymentDateOverride override) {
        return resolve(totalAmount, override, getCategoryLimits());
    }

    private CreditLimitResolution buildResolution(
            double totalAmount,
            Double overrideLimit,
            Double effectiveLimit,
            String source
    ) {
        if (effectiveLimit == null) {
            return new CreditLimitResolution(overrideLimit, null, null, false, null);
        }
        double limit = effectiveLimit;
        boolean over = totalAmount > limit;
        Double utilization;
        if (limit > 0) {
            utilization = totalAmount / limit;
        } else if (totalAmount > 0) {
            utilization = null;
        } else {
            utilization = 0.0;
        }
        return new CreditLimitResolution(overrideLimit, limit, source, over, utilization);
    }

    private CustomerCreditLimitDocument ensureDocument() {
        return repository.findById(CustomerCreditLimitDocument.DOCUMENT_ID)
                .orElseGet(() -> {
                    CustomerCreditLimitDocument seed = new CustomerCreditLimitDocument();
                    seed.setId(CustomerCreditLimitDocument.DOCUMENT_ID);
                    seed.setLimits(new HashMap<>(SEED_DEFAULTS));
                    seed.setUpdatedAt(Instant.now());
                    seed.setUpdatedBy("system");
                    return repository.save(seed);
                });
    }

    private static Map<String, Double> normalizeLimits(Map<String, Double> limits) {
        Map<String, Double> out = new HashMap<>();
        for (String category : VALID_CATEGORIES) {
            Double value = limits != null ? limits.get(category) : null;
            if (value == null) {
                value = SEED_DEFAULTS.get(category);
            }
            if (value < 0) {
                throw new IllegalArgumentException("Credit limit cannot be negative for category " + category);
            }
            out.put(category, value);
        }
        return out;
    }
}
