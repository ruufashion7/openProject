package org.example.creditrisk;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "credit_limit_audits")
public class CreditLimitAuditDocument {

    @Id
    private String id;
    private String customerKey;
    private String customerName;
    private Double previousLimit;
    private Double newLimit;
    private String source;
    private String changedBy;
    private Instant changedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCustomerKey() {
        return customerKey;
    }

    public void setCustomerKey(String customerKey) {
        this.customerKey = customerKey;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public Double getPreviousLimit() {
        return previousLimit;
    }

    public void setPreviousLimit(Double previousLimit) {
        this.previousLimit = previousLimit;
    }

    public Double getNewLimit() {
        return newLimit;
    }

    public void setNewLimit(Double newLimit) {
        this.newLimit = newLimit;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getChangedBy() {
        return changedBy;
    }

    public void setChangedBy(String changedBy) {
        this.changedBy = changedBy;
    }

    public Instant getChangedAt() {
        return changedAt;
    }

    public void setChangedAt(Instant changedAt) {
        this.changedAt = changedAt;
    }
}
