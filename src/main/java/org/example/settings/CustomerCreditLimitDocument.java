package org.example.settings;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Singleton org-wide default credit limits per customer category.
 */
@Document(collection = "app_settings")
public class CustomerCreditLimitDocument {

    public static final String DOCUMENT_ID = "customer_credit_limits";

    @Id
    private String id = DOCUMENT_ID;

    private Map<String, Double> limits = new HashMap<>();

    private Instant updatedAt;

    private String updatedBy;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Map<String, Double> getLimits() {
        return limits;
    }

    public void setLimits(Map<String, Double> limits) {
        this.limits = limits != null ? limits : new HashMap<>();
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }
}
