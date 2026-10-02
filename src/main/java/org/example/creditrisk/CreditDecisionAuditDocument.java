package org.example.creditrisk;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Document(collection = "credit_decision_audit")
public class CreditDecisionAuditDocument {

    @Id
    private String id;
    private String customerKey;
    private String customerName;
    private double orderAmount;
    private String referenceId;
    private String orderDecision;
    private String paymentDecision;
    private double paymentScore;
    private double businessScore;
    private double riskScore;
    private String riskCategory;
    private Double creditLimit;
    private double currentOutstanding;
    private double overdueAmount;
    private double projectedExposure;
    private double requiredPayment;
    private List<DecisionReason> reasons = new ArrayList<>();
    private String decidedBy;
    private Instant decidedAt;
    private String originalOrderDecision;
    private String overrideOrderDecision;
    private String overrideReason;
    private String overrideBy;
    private Instant overrideAt;

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

    public double getOrderAmount() {
        return orderAmount;
    }

    public void setOrderAmount(double orderAmount) {
        this.orderAmount = orderAmount;
    }

    public String getReferenceId() {
        return referenceId;
    }

    public void setReferenceId(String referenceId) {
        this.referenceId = referenceId;
    }

    public String getOrderDecision() {
        return orderDecision;
    }

    public void setOrderDecision(String orderDecision) {
        this.orderDecision = orderDecision;
    }

    public String getPaymentDecision() {
        return paymentDecision;
    }

    public void setPaymentDecision(String paymentDecision) {
        this.paymentDecision = paymentDecision;
    }

    public double getPaymentScore() {
        return paymentScore;
    }

    public void setPaymentScore(double paymentScore) {
        this.paymentScore = paymentScore;
    }

    public double getBusinessScore() {
        return businessScore;
    }

    public void setBusinessScore(double businessScore) {
        this.businessScore = businessScore;
    }

    public double getRiskScore() {
        return riskScore;
    }

    public void setRiskScore(double riskScore) {
        this.riskScore = riskScore;
    }

    public String getRiskCategory() {
        return riskCategory;
    }

    public void setRiskCategory(String riskCategory) {
        this.riskCategory = riskCategory;
    }

    public Double getCreditLimit() {
        return creditLimit;
    }

    public void setCreditLimit(Double creditLimit) {
        this.creditLimit = creditLimit;
    }

    public double getCurrentOutstanding() {
        return currentOutstanding;
    }

    public void setCurrentOutstanding(double currentOutstanding) {
        this.currentOutstanding = currentOutstanding;
    }

    public double getOverdueAmount() {
        return overdueAmount;
    }

    public void setOverdueAmount(double overdueAmount) {
        this.overdueAmount = overdueAmount;
    }

    public double getProjectedExposure() {
        return projectedExposure;
    }

    public void setProjectedExposure(double projectedExposure) {
        this.projectedExposure = projectedExposure;
    }

    public double getRequiredPayment() {
        return requiredPayment;
    }

    public void setRequiredPayment(double requiredPayment) {
        this.requiredPayment = requiredPayment;
    }

    public List<DecisionReason> getReasons() {
        return reasons;
    }

    public void setReasons(List<DecisionReason> reasons) {
        this.reasons = reasons != null ? reasons : new ArrayList<>();
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public void setDecidedBy(String decidedBy) {
        this.decidedBy = decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(Instant decidedAt) {
        this.decidedAt = decidedAt;
    }

    public String getOriginalOrderDecision() {
        return originalOrderDecision;
    }

    public void setOriginalOrderDecision(String originalOrderDecision) {
        this.originalOrderDecision = originalOrderDecision;
    }

    public String getOverrideOrderDecision() {
        return overrideOrderDecision;
    }

    public void setOverrideOrderDecision(String overrideOrderDecision) {
        this.overrideOrderDecision = overrideOrderDecision;
    }

    public String getOverrideReason() {
        return overrideReason;
    }

    public void setOverrideReason(String overrideReason) {
        this.overrideReason = overrideReason;
    }

    public String getOverrideBy() {
        return overrideBy;
    }

    public void setOverrideBy(String overrideBy) {
        this.overrideBy = overrideBy;
    }

    public Instant getOverrideAt() {
        return overrideAt;
    }

    public void setOverrideAt(Instant overrideAt) {
        this.overrideAt = overrideAt;
    }
}
