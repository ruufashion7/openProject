package org.example.creditrisk;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Document(collection = "credit_risk_snapshots")
public class CreditRiskSnapshotDocument {

    @Id
    private String id;

    @Indexed(unique = true)
    private String customerKey;
    private String customerName;
    private String customerCategory;
    private double paymentScore;
    private double businessScore;
    private double riskScore;
    private String riskCategory;
    private String paymentBehaviour;
    private String businessValue;
    private Double creditLimit;
    private double currentOutstanding;
    private double overdueAmount;
    private int overdueInvoiceCount;
    private int maximumOverdueDays;
    private Double creditUtilization;
    private double onTimePaymentPercentage;
    private double averagePaymentDelayDays;
    private double salesLast30Days;
    private double salesLast90Days;
    private double salesLast180Days;
    private double salesLast365Days;
    private double lifetimeSales;
    private int totalOrders;
    private double averageOrderValue;
    private String lastPaymentDate;
    private String lastOrderDate;
    private Double recommendedCreditLimit;
    private List<String> alerts = new ArrayList<>();
    private List<String> dataQualityWarnings = new ArrayList<>();
    /** BLOCKED | NEED_PAYMENT | NEED_APPROVAL | WATCH | CLEAR */
    private String actionBucket;
    private String suggestedOrderDecision;
    private boolean manualHold;
    private String phoneNumber;
    private Instant updatedAt;

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

    public String getCustomerCategory() {
        return customerCategory;
    }

    public void setCustomerCategory(String customerCategory) {
        this.customerCategory = customerCategory;
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

    public String getPaymentBehaviour() {
        return paymentBehaviour;
    }

    public void setPaymentBehaviour(String paymentBehaviour) {
        this.paymentBehaviour = paymentBehaviour;
    }

    public String getBusinessValue() {
        return businessValue;
    }

    public void setBusinessValue(String businessValue) {
        this.businessValue = businessValue;
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

    public int getOverdueInvoiceCount() {
        return overdueInvoiceCount;
    }

    public void setOverdueInvoiceCount(int overdueInvoiceCount) {
        this.overdueInvoiceCount = overdueInvoiceCount;
    }

    public int getMaximumOverdueDays() {
        return maximumOverdueDays;
    }

    public void setMaximumOverdueDays(int maximumOverdueDays) {
        this.maximumOverdueDays = maximumOverdueDays;
    }

    public Double getCreditUtilization() {
        return creditUtilization;
    }

    public void setCreditUtilization(Double creditUtilization) {
        this.creditUtilization = creditUtilization;
    }

    public double getOnTimePaymentPercentage() {
        return onTimePaymentPercentage;
    }

    public void setOnTimePaymentPercentage(double onTimePaymentPercentage) {
        this.onTimePaymentPercentage = onTimePaymentPercentage;
    }

    public double getAveragePaymentDelayDays() {
        return averagePaymentDelayDays;
    }

    public void setAveragePaymentDelayDays(double averagePaymentDelayDays) {
        this.averagePaymentDelayDays = averagePaymentDelayDays;
    }

    public double getSalesLast30Days() {
        return salesLast30Days;
    }

    public void setSalesLast30Days(double salesLast30Days) {
        this.salesLast30Days = salesLast30Days;
    }

    public double getSalesLast90Days() {
        return salesLast90Days;
    }

    public void setSalesLast90Days(double salesLast90Days) {
        this.salesLast90Days = salesLast90Days;
    }

    public double getSalesLast180Days() {
        return salesLast180Days;
    }

    public void setSalesLast180Days(double salesLast180Days) {
        this.salesLast180Days = salesLast180Days;
    }

    public double getSalesLast365Days() {
        return salesLast365Days;
    }

    public void setSalesLast365Days(double salesLast365Days) {
        this.salesLast365Days = salesLast365Days;
    }

    public double getLifetimeSales() {
        return lifetimeSales;
    }

    public void setLifetimeSales(double lifetimeSales) {
        this.lifetimeSales = lifetimeSales;
    }

    public int getTotalOrders() {
        return totalOrders;
    }

    public void setTotalOrders(int totalOrders) {
        this.totalOrders = totalOrders;
    }

    public double getAverageOrderValue() {
        return averageOrderValue;
    }

    public void setAverageOrderValue(double averageOrderValue) {
        this.averageOrderValue = averageOrderValue;
    }

    public String getLastPaymentDate() {
        return lastPaymentDate;
    }

    public void setLastPaymentDate(String lastPaymentDate) {
        this.lastPaymentDate = lastPaymentDate;
    }

    public String getLastOrderDate() {
        return lastOrderDate;
    }

    public void setLastOrderDate(String lastOrderDate) {
        this.lastOrderDate = lastOrderDate;
    }

    public Double getRecommendedCreditLimit() {
        return recommendedCreditLimit;
    }

    public void setRecommendedCreditLimit(Double recommendedCreditLimit) {
        this.recommendedCreditLimit = recommendedCreditLimit;
    }

    public List<String> getAlerts() {
        return alerts;
    }

    public void setAlerts(List<String> alerts) {
        this.alerts = alerts != null ? alerts : new ArrayList<>();
    }

    public List<String> getDataQualityWarnings() {
        return dataQualityWarnings;
    }

    public void setDataQualityWarnings(List<String> dataQualityWarnings) {
        this.dataQualityWarnings = dataQualityWarnings != null ? dataQualityWarnings : new ArrayList<>();
    }

    public String getActionBucket() {
        return actionBucket;
    }

    public void setActionBucket(String actionBucket) {
        this.actionBucket = actionBucket;
    }

    public String getSuggestedOrderDecision() {
        return suggestedOrderDecision;
    }

    public void setSuggestedOrderDecision(String suggestedOrderDecision) {
        this.suggestedOrderDecision = suggestedOrderDecision;
    }

    public boolean isManualHold() {
        return manualHold;
    }

    public void setManualHold(boolean manualHold) {
        this.manualHold = manualHold;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
