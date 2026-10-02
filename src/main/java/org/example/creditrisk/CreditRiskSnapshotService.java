package org.example.creditrisk;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class CreditRiskSnapshotService {

    public static final String BUCKET_BLOCKED = "BLOCKED";
    public static final String BUCKET_NEED_PAYMENT = "NEED_PAYMENT";
    public static final String BUCKET_NEED_APPROVAL = "NEED_APPROVAL";
    public static final String BUCKET_WATCH = "WATCH";
    public static final String BUCKET_WIND_DOWN = "WIND_DOWN";
    public static final String BUCKET_CLEAR = "CLEAR";

    private final CreditRiskSnapshotRepository repository;

    public CreditRiskSnapshotService(CreditRiskSnapshotRepository repository) {
        this.repository = repository;
    }

    public CreditRiskSnapshotDocument upsert(
            CustomerRiskMetrics m,
            CustomerRiskScore score,
            Double recommended,
            String suggestedOrderDecision,
            String actionBucket
    ) {
        CreditRiskSnapshotDocument doc = repository.findByCustomerKey(m.getCustomerKey())
                .orElseGet(CreditRiskSnapshotDocument::new);
        doc.setCustomerKey(m.getCustomerKey());
        doc.setCustomerName(m.getCustomerName());
        doc.setCustomerCategory(m.getCustomerCategory());
        doc.setPhoneNumber(m.getPhoneNumber());
        doc.setManualHold(m.isManualHold());
        doc.setPaymentScore(score.paymentScore());
        doc.setBusinessScore(score.businessScore());
        doc.setRiskScore(score.riskScore());
        doc.setRiskCategory(score.riskCategory());
        doc.setPaymentBehaviour(score.paymentBehaviour());
        doc.setBusinessValue(score.businessValue());
        doc.setCreditLimit(m.getEffectiveCreditLimit());
        doc.setCurrentOutstanding(m.getCurrentOutstanding());
        doc.setOverdueAmount(m.getOverdueAmount());
        doc.setOverdueInvoiceCount(m.getOverdueInvoiceCount());
        doc.setMaximumOverdueDays(m.getMaximumOverdueDays());
        doc.setCreditUtilization(m.getCreditUtilization());
        doc.setOnTimePaymentPercentage(m.getOnTimePaymentPercentage());
        doc.setAveragePaymentDelayDays(m.getAveragePaymentDelayDays());
        doc.setSalesLast30Days(m.getSalesLast30Days());
        doc.setSalesLast90Days(m.getSalesLast90Days());
        doc.setSalesLast180Days(m.getSalesLast180Days());
        doc.setSalesLast365Days(m.getSalesLast365Days());
        doc.setLifetimeSales(m.getLifetimeSales());
        doc.setTotalOrders(m.getTotalOrders());
        doc.setAverageOrderValue(m.getAverageOrderValue());
        doc.setLastPaymentDate(m.getLastPaymentDate());
        doc.setLastOrderDate(m.getLastOrderDate());
        doc.setRecommendedCreditLimit(recommended);
        doc.setAlerts(m.getAlerts());
        doc.setDataQualityWarnings(m.getDataQualityWarnings());
        doc.setSuggestedOrderDecision(suggestedOrderDecision);
        doc.setActionBucket(actionBucket != null ? actionBucket : classifyBucket(suggestedOrderDecision, score.riskCategory(), m.getAlerts(), m));
        doc.setUpdatedAt(Instant.now());
        return repository.save(doc);
    }

    public CreditRiskSnapshotDocument upsert(CustomerRiskMetrics m, CustomerRiskScore score, Double recommended) {
        return upsert(m, score, recommended, null, null);
    }

    public static String classifyBucket(String orderDecision, String riskCategory) {
        return classifyBucket(orderDecision, riskCategory, null, null);
    }

    public static String classifyBucket(String orderDecision, String riskCategory, List<String> alerts) {
        return classifyBucket(orderDecision, riskCategory, alerts, null);
    }

    public static String classifyBucket(
            String orderDecision,
            String riskCategory,
            List<String> alerts,
            CustomerRiskMetrics metrics
    ) {
        if (metrics != null && metrics.getBrokenPaymentPromises() >= 2) {
            return BUCKET_NEED_APPROVAL;
        }
        if (orderDecision != null) {
            if (OrderDecisionResult.MANUAL_HOLD.equals(orderDecision)
                    || OrderDecisionResult.DO_NOT_TAKE_ORDER.equals(orderDecision)) {
                return BUCKET_BLOCKED;
            }
            if (OrderDecisionResult.TAKE_ORDER_AFTER_PAYMENT.equals(orderDecision)) {
                return BUCKET_NEED_PAYMENT;
            }
            if (OrderDecisionResult.TAKE_ORDER_WITH_APPROVAL.equals(orderDecision)) {
                return BUCKET_NEED_APPROVAL;
            }
        }
        if (alerts != null
                && (alerts.contains("COLLECT_CASH_DUE") || alerts.contains("PAYMENT_PROMISE_BROKEN"))) {
            return BUCKET_NEED_PAYMENT;
        }
        if (alerts != null && isWindDown(alerts)) {
            return BUCKET_WIND_DOWN;
        }
        // Healthy book: outstanding within terms (no overdue) and rules allow supply → grow sales, not "watch list".
        if (metrics != null
                && OrderDecisionResult.TAKE_ORDER.equals(orderDecision)
                && metrics.getOverdueAmount() <= 0.01) {
            return BUCKET_CLEAR;
        }
        if (riskCategory != null) {
            String r = riskCategory.trim().toUpperCase(Locale.ROOT);
            if ("WATCH".equals(r) || "RISKY".equals(r) || "HIGH_RISK".equals(r)) {
                return BUCKET_WATCH;
            }
        }
        return BUCKET_CLEAR;
    }

    /**
     * Growth queue: safe to pursue new sales (typical order) while still flagging blocked / pay-first / wind-down.
     */
    public static boolean isGrowSalesEligible(CreditRiskSnapshotDocument s) {
        if (s == null || s.isManualHold()) {
            return false;
        }
        String bucket = s.getActionBucket();
        if (BUCKET_BLOCKED.equals(bucket)
                || BUCKET_NEED_PAYMENT.equals(bucket)
                || BUCKET_WIND_DOWN.equals(bucket)) {
            return false;
        }
        String decision = s.getSuggestedOrderDecision();
        if (OrderDecisionResult.TAKE_ORDER.equals(decision)) {
            return true;
        }
        if (OrderDecisionResult.TAKE_ORDER_WITH_APPROVAL.equals(decision)) {
            String r = s.getRiskCategory();
            if (r == null) {
                return false;
            }
            r = r.trim().toUpperCase(Locale.ROOT);
            return "VERY_RELIABLE".equals(r) || "RELIABLE".equals(r);
        }
        return false;
    }

    private static boolean isWindDown(List<String> alerts) {
        if (!alerts.contains("ORDER_VALUE_DECLINING")) {
            return false;
        }
        return alerts.contains("OVERDUE_AMOUNT_INCREASING")
                || alerts.contains("PAYMENT_FAILURE_INCREASED")
                || alerts.contains("PAYMENT_PROMISE_BROKEN");
    }

    public List<CreditRiskSnapshotDocument> listAll() {
        return repository.findAll();
    }

    public List<CreditRiskSnapshotDocument> listByBucket(String bucket) {
        if (bucket == null || bucket.isBlank() || "ALL".equalsIgnoreCase(bucket)) {
            return listAll().stream()
                    .filter(s -> s.getActionBucket() != null && !BUCKET_CLEAR.equals(s.getActionBucket()))
                    .collect(Collectors.toList());
        }
        String b = bucket.trim().toUpperCase(Locale.ROOT);
        if ("GROW".equals(b) || "READY".equals(b)) {
            return listAll().stream()
                    .filter(CreditRiskSnapshotService::isGrowSalesEligible)
                    .collect(Collectors.toList());
        }
        return listAll().stream()
                .filter(s -> b.equalsIgnoreCase(s.getActionBucket()))
                .collect(Collectors.toList());
    }

    public Optional<CreditRiskSnapshotDocument> find(String customerKey) {
        return repository.findByCustomerKey(customerKey);
    }
}
