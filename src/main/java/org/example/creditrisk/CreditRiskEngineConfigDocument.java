package org.example.creditrisk;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Singleton thresholds and scoring bands for the credit risk engine.
 */
@Document(collection = "app_settings")
public class CreditRiskEngineConfigDocument {

    public static final String DOCUMENT_ID = "credit_risk_engine_config";

    /** Soft for RELIABLE+; hard for WATCH/RISKY/HIGH_RISK. Also HARD_BLOCK / TAKE_ORDER_AFTER_PAYMENT. */
    public static final String CREDIT_LIMIT_BY_RISK = "BY_RISK_CATEGORY";

    @Id
    private String id = DOCUMENT_ID;

    /** Fallback when category map has no match. */
    private int overdueHardBlockDays = 90;
    private int noPaymentAgainstDueDays = 30;
    private int newCustomerMaxTenureDays = 90;
    private int newCustomerInvoiceThreshold = 3;
    /** BY_RISK_CATEGORY (default), HARD_BLOCK, or TAKE_ORDER_AFTER_PAYMENT */
    private String creditLimitBreachMode = CREDIT_LIMIT_BY_RISK;
    /** Overdue hard-block days by customer category (A/B/semi-wholesale). C is blocked separately. */
    private Map<String, Integer> overdueHardBlockDaysByCategory = defaultOverdueByCategory();
    /** Hybrid terms fallback: invoice date + these days when due/terms columns missing. */
    private Map<String, Integer> defaultPaymentTermsDaysByCategory = defaultPaymentTermsByCategory();
    private int multipleOverdueInvoiceThreshold = 3;
    private double highOverduePercentThreshold = 50.0;
    private double lowOnTimePaymentPercent = 70.0;
    private int repeatedLatePaymentCount = 3;
    private int paymentFailureWindowDays = 90;
    private int paymentFailureCountThreshold = 2;
    private double creditUtilizationWatchPercent = 70.0;
    private double creditUtilizationHighPercent = 90.0;
    private double unusualOrderMultiplier = 5.0;
    private boolean enablePaymentPromiseRule = true;
    private boolean enablePaymentFailureRule = true;
    private boolean enableNoPaymentAgainstDueRule = false;
    private boolean redistributeMarginWeight = true;
    private boolean enableLowMarginWithOverdueRule = true;
    private double lowMarginPercentThreshold = 8.0;
    /** Open due below this (₹) is ignored for max ageing / amount-weighted avg delay only. */
    private double minOpenDueForAgeingMetrics = 100.0;

    private int riskCategoryVeryReliableMin = 85;
    private int riskCategoryReliableMin = 70;
    private int riskCategoryWatchMin = 55;
    private int riskCategoryRiskyMin = 40;

    /** Raw-value → points maps keyed by component id (optional overrides). */
    private Map<String, Map<String, Double>> scoringBands = new HashMap<>();

    private Instant updatedAt;
    private String updatedBy;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public int getOverdueHardBlockDays() {
        return overdueHardBlockDays;
    }

    public void setOverdueHardBlockDays(int overdueHardBlockDays) {
        this.overdueHardBlockDays = overdueHardBlockDays;
    }

    public int getNoPaymentAgainstDueDays() {
        return noPaymentAgainstDueDays;
    }

    public void setNoPaymentAgainstDueDays(int noPaymentAgainstDueDays) {
        this.noPaymentAgainstDueDays = noPaymentAgainstDueDays;
    }

    public int getNewCustomerMaxTenureDays() {
        return newCustomerMaxTenureDays;
    }

    public void setNewCustomerMaxTenureDays(int newCustomerMaxTenureDays) {
        this.newCustomerMaxTenureDays = newCustomerMaxTenureDays;
    }

    public int getNewCustomerInvoiceThreshold() {
        return newCustomerInvoiceThreshold;
    }

    public void setNewCustomerInvoiceThreshold(int newCustomerInvoiceThreshold) {
        this.newCustomerInvoiceThreshold = newCustomerInvoiceThreshold;
    }

    public String getCreditLimitBreachMode() {
        return creditLimitBreachMode;
    }

    public void setCreditLimitBreachMode(String creditLimitBreachMode) {
        this.creditLimitBreachMode = creditLimitBreachMode;
    }

    public Map<String, Integer> getOverdueHardBlockDaysByCategory() {
        return overdueHardBlockDaysByCategory;
    }

    public void setOverdueHardBlockDaysByCategory(Map<String, Integer> overdueHardBlockDaysByCategory) {
        this.overdueHardBlockDaysByCategory =
                overdueHardBlockDaysByCategory != null ? overdueHardBlockDaysByCategory : defaultOverdueByCategory();
    }

    public Map<String, Integer> getDefaultPaymentTermsDaysByCategory() {
        return defaultPaymentTermsDaysByCategory;
    }

    public void setDefaultPaymentTermsDaysByCategory(Map<String, Integer> defaultPaymentTermsDaysByCategory) {
        this.defaultPaymentTermsDaysByCategory = defaultPaymentTermsDaysByCategory != null
                ? defaultPaymentTermsDaysByCategory
                : defaultPaymentTermsByCategory();
    }

    /** Category overdue threshold; falls back to {@link #overdueHardBlockDays}. */
    public int overdueHardBlockDaysForCategory(String customerCategory) {
        Integer mapped = lookupCategoryDays(overdueHardBlockDaysByCategory, customerCategory);
        return mapped != null ? mapped : overdueHardBlockDays;
    }

    /** Category default payment terms (days); 0 if unknown. */
    public int defaultPaymentTermsDaysForCategory(String customerCategory) {
        Integer mapped = lookupCategoryDays(defaultPaymentTermsDaysByCategory, customerCategory);
        return mapped != null ? mapped : 0;
    }

    /**
     * Soft (payment required) vs hard block for credit-limit breach.
     * BY_RISK_CATEGORY: soft for VERY_RELIABLE/RELIABLE; hard otherwise.
     */
    public boolean isSoftCreditLimitBreach(String riskCategory) {
        String mode = creditLimitBreachMode != null ? creditLimitBreachMode.trim() : CREDIT_LIMIT_BY_RISK;
        if ("HARD_BLOCK".equalsIgnoreCase(mode)) {
            return false;
        }
        if ("TAKE_ORDER_AFTER_PAYMENT".equalsIgnoreCase(mode)) {
            return true;
        }
        // BY_RISK_CATEGORY (default)
        if (riskCategory == null) {
            return false;
        }
        String r = riskCategory.trim().toUpperCase(Locale.ROOT);
        return "VERY_RELIABLE".equals(r) || "RELIABLE".equals(r);
    }

    private static Integer lookupCategoryDays(Map<String, Integer> map, String customerCategory) {
        if (map == null || map.isEmpty() || customerCategory == null || customerCategory.isBlank()) {
            return null;
        }
        Integer exact = map.get(customerCategory);
        if (exact != null) {
            return exact;
        }
        String key = customerCategory.trim();
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) {
                return e.getValue();
            }
        }
        String norm = key.toLowerCase(Locale.ROOT).replace('_', '-').replace(' ', '-');
        if (norm.contains("semi") && norm.contains("wholesale")) {
            return map.getOrDefault("semi-wholesale", map.get("SEMI_WHOLESALE"));
        }
        return map.get(norm);
    }

    private static Map<String, Integer> defaultOverdueByCategory() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("A", 90);
        m.put("B", 60);
        m.put("semi-wholesale", 120);
        return m;
    }

    private static Map<String, Integer> defaultPaymentTermsByCategory() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("A", 30);
        m.put("B", 15);
        m.put("semi-wholesale", 45);
        return m;
    }

    public int getMultipleOverdueInvoiceThreshold() {
        return multipleOverdueInvoiceThreshold;
    }

    public void setMultipleOverdueInvoiceThreshold(int multipleOverdueInvoiceThreshold) {
        this.multipleOverdueInvoiceThreshold = multipleOverdueInvoiceThreshold;
    }

    public double getHighOverduePercentThreshold() {
        return highOverduePercentThreshold;
    }

    public void setHighOverduePercentThreshold(double highOverduePercentThreshold) {
        this.highOverduePercentThreshold = highOverduePercentThreshold;
    }

    public double getLowOnTimePaymentPercent() {
        return lowOnTimePaymentPercent;
    }

    public void setLowOnTimePaymentPercent(double lowOnTimePaymentPercent) {
        this.lowOnTimePaymentPercent = lowOnTimePaymentPercent;
    }

    public int getRepeatedLatePaymentCount() {
        return repeatedLatePaymentCount;
    }

    public void setRepeatedLatePaymentCount(int repeatedLatePaymentCount) {
        this.repeatedLatePaymentCount = repeatedLatePaymentCount;
    }

    public int getPaymentFailureWindowDays() {
        return paymentFailureWindowDays;
    }

    public void setPaymentFailureWindowDays(int paymentFailureWindowDays) {
        this.paymentFailureWindowDays = paymentFailureWindowDays;
    }

    public int getPaymentFailureCountThreshold() {
        return paymentFailureCountThreshold;
    }

    public void setPaymentFailureCountThreshold(int paymentFailureCountThreshold) {
        this.paymentFailureCountThreshold = paymentFailureCountThreshold;
    }

    public double getCreditUtilizationWatchPercent() {
        return creditUtilizationWatchPercent;
    }

    public void setCreditUtilizationWatchPercent(double creditUtilizationWatchPercent) {
        this.creditUtilizationWatchPercent = creditUtilizationWatchPercent;
    }

    public double getCreditUtilizationHighPercent() {
        return creditUtilizationHighPercent;
    }

    public void setCreditUtilizationHighPercent(double creditUtilizationHighPercent) {
        this.creditUtilizationHighPercent = creditUtilizationHighPercent;
    }

    public double getUnusualOrderMultiplier() {
        return unusualOrderMultiplier;
    }

    public void setUnusualOrderMultiplier(double unusualOrderMultiplier) {
        this.unusualOrderMultiplier = unusualOrderMultiplier;
    }

    public boolean isEnablePaymentPromiseRule() {
        return enablePaymentPromiseRule;
    }

    public void setEnablePaymentPromiseRule(boolean enablePaymentPromiseRule) {
        this.enablePaymentPromiseRule = enablePaymentPromiseRule;
    }

    public boolean isEnablePaymentFailureRule() {
        return enablePaymentFailureRule;
    }

    public void setEnablePaymentFailureRule(boolean enablePaymentFailureRule) {
        this.enablePaymentFailureRule = enablePaymentFailureRule;
    }

    public boolean isEnableNoPaymentAgainstDueRule() {
        return enableNoPaymentAgainstDueRule;
    }

    public void setEnableNoPaymentAgainstDueRule(boolean enableNoPaymentAgainstDueRule) {
        this.enableNoPaymentAgainstDueRule = enableNoPaymentAgainstDueRule;
    }

    public boolean isRedistributeMarginWeight() {
        return redistributeMarginWeight;
    }

    public void setRedistributeMarginWeight(boolean redistributeMarginWeight) {
        this.redistributeMarginWeight = redistributeMarginWeight;
    }

    public boolean isEnableLowMarginWithOverdueRule() {
        return enableLowMarginWithOverdueRule;
    }

    public void setEnableLowMarginWithOverdueRule(boolean enableLowMarginWithOverdueRule) {
        this.enableLowMarginWithOverdueRule = enableLowMarginWithOverdueRule;
    }

    public double getLowMarginPercentThreshold() {
        return lowMarginPercentThreshold;
    }

    public void setLowMarginPercentThreshold(double lowMarginPercentThreshold) {
        this.lowMarginPercentThreshold = lowMarginPercentThreshold;
    }

    public double getMinOpenDueForAgeingMetrics() {
        return minOpenDueForAgeingMetrics;
    }

    public void setMinOpenDueForAgeingMetrics(double minOpenDueForAgeingMetrics) {
        this.minOpenDueForAgeingMetrics = minOpenDueForAgeingMetrics;
    }

    public int getRiskCategoryVeryReliableMin() {
        return riskCategoryVeryReliableMin;
    }

    public void setRiskCategoryVeryReliableMin(int riskCategoryVeryReliableMin) {
        this.riskCategoryVeryReliableMin = riskCategoryVeryReliableMin;
    }

    public int getRiskCategoryReliableMin() {
        return riskCategoryReliableMin;
    }

    public void setRiskCategoryReliableMin(int riskCategoryReliableMin) {
        this.riskCategoryReliableMin = riskCategoryReliableMin;
    }

    public int getRiskCategoryWatchMin() {
        return riskCategoryWatchMin;
    }

    public void setRiskCategoryWatchMin(int riskCategoryWatchMin) {
        this.riskCategoryWatchMin = riskCategoryWatchMin;
    }

    public int getRiskCategoryRiskyMin() {
        return riskCategoryRiskyMin;
    }

    public void setRiskCategoryRiskyMin(int riskCategoryRiskyMin) {
        this.riskCategoryRiskyMin = riskCategoryRiskyMin;
    }

    public Map<String, Map<String, Double>> getScoringBands() {
        return scoringBands;
    }

    public void setScoringBands(Map<String, Map<String, Double>> scoringBands) {
        this.scoringBands = scoringBands != null ? scoringBands : new HashMap<>();
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
