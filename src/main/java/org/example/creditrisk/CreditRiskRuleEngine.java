package org.example.creditrisk;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class CreditRiskRuleEngine {

    public List<DecisionReason> evaluate(
            CustomerRiskMetrics m,
            CustomerRiskScore score,
            double orderAmount,
            double reservedExposure,
            CreditRiskEngineConfigDocument cfg
    ) {
        List<DecisionReason> reasons = new ArrayList<>();

        // 1. Manual hold
        if (m.isManualHold()) {
            reasons.add(new DecisionReason(
                    "MANUAL_HOLD",
                    DecisionReason.HARD_BLOCK,
                    "Customer is on manual hold" + (m.getManualHoldReason() != null ? ": " + m.getManualHoldReason() : "")
            ));
        }

        // 2. Category C
        if ("C".equalsIgnoreCase(m.getCustomerCategory())) {
            reasons.add(new DecisionReason(
                    "CUSTOMER_STATUS_C",
                    DecisionReason.HARD_BLOCK,
                    "Customer category is C — orders are not accepted on credit"
            ));
        }

        // 3. Severe overdue (threshold by customer category; C already hard-blocked above)
        int blockDays = cfg.overdueHardBlockDaysForCategory(m.getCustomerCategory());
        if (m.getMaximumOverdueDays() > blockDays) {
            reasons.add(new DecisionReason(
                    "INVOICE_OVERDUE_HARD_BLOCK",
                    DecisionReason.HARD_BLOCK,
                    "Open invoice overdue by " + m.getMaximumOverdueDays() + " days (category "
                            + (m.getCustomerCategory() != null ? m.getCustomerCategory() : "?")
                            + " threshold " + blockDays + ")"
            ));
        }

        // 4. No payment against due
        if (cfg.isEnableNoPaymentAgainstDueRule()
                && m.getDaysSincePaymentAgainstDue() != null
                && m.getDaysSincePaymentAgainstDue() > cfg.getNoPaymentAgainstDueDays()
                && m.getOverdueAmount() > 0) {
            reasons.add(new DecisionReason(
                    "NO_PAYMENT_AGAINST_DUE_INVOICES_30_DAYS",
                    DecisionReason.HARD_BLOCK,
                    "No payment against due invoices for " + m.getDaysSincePaymentAgainstDue() + " days"
            ));
        }

        // 5. Credit limit / projected exposure — soft for RELIABLE+ when BY_RISK_CATEGORY
        Double limit = m.getEffectiveCreditLimit();
        double outstanding = m.getCurrentOutstanding();
        double projected = outstanding + orderAmount + reservedExposure;
        String riskCat = score != null ? score.riskCategory() : null;
        boolean softLimit = cfg.isSoftCreditLimitBreach(riskCat);
        if (limit != null) {
            if (outstanding > limit) {
                reasons.add(new DecisionReason(
                        "CREDIT_LIMIT_EXCEEDED",
                        softLimit ? DecisionReason.PAYMENT_REQUIRED : DecisionReason.HARD_BLOCK,
                        "Current outstanding ₹" + fmt(outstanding) + " exceeds credit limit ₹" + fmt(limit)
                ));
            } else if (projected > limit) {
                double required = projected - limit;
                reasons.add(new DecisionReason(
                        "CREDIT_LIMIT_EXCEEDED",
                        softLimit ? DecisionReason.PAYMENT_REQUIRED : DecisionReason.HARD_BLOCK,
                        softLimit
                                ? "Projected exposure ₹" + fmt(projected) + " exceeds credit limit ₹" + fmt(limit)
                                + "; required payment ₹" + fmt(required)
                                : "Projected exposure ₹" + fmt(projected) + " exceeds credit limit ₹" + fmt(limit)
                ));
            }
        }

        // 6. New customer no payment
        boolean isNew = m.getCustomerTenureDays() > 0
                && m.getCustomerTenureDays() <= cfg.getNewCustomerMaxTenureDays();
        if (isNew
                && m.getTotalInvoices() >= cfg.getNewCustomerInvoiceThreshold()
                && m.getSuccessfulPaymentCount() == 0) {
            reasons.add(new DecisionReason(
                    "NEW_CUSTOMER_NO_PAYMENT_HISTORY",
                    DecisionReason.HARD_BLOCK,
                    "New customer with " + m.getTotalInvoices() + " invoices and zero successful payments"
            ));
        }

        // Soft rules
        if (m.getOverdueInvoiceCount() >= cfg.getMultipleOverdueInvoiceThreshold()) {
            reasons.add(new DecisionReason(
                    "MULTIPLE_OVERDUE_INVOICES",
                    DecisionReason.APPROVAL,
                    m.getOverdueInvoiceCount() + " overdue invoices"
            ));
        }

        if (outstanding > 0) {
            double overduePct = 100.0 * m.getOverdueAmount() / outstanding;
            if (overduePct >= cfg.getHighOverduePercentThreshold()) {
                reasons.add(new DecisionReason(
                        "HIGH_OVERDUE_AMOUNT",
                        DecisionReason.APPROVAL,
                        String.format("Overdue is %.0f%% of outstanding", overduePct)
                ));
            }
        }

        if (m.getPaidInvoices() > 0 && m.getOnTimePaymentPercentage() < cfg.getLowOnTimePaymentPercent()) {
            reasons.add(new DecisionReason(
                    "LOW_ON_TIME_PAYMENT",
                    DecisionReason.APPROVAL,
                    String.format("On-time payment %.1f%% below threshold %.1f%%",
                            m.getOnTimePaymentPercentage(), cfg.getLowOnTimePaymentPercent())
            ));
        }

        if (m.getLatePaymentCount() >= cfg.getRepeatedLatePaymentCount()) {
            reasons.add(new DecisionReason(
                    "REPEATED_LATE_PAYMENTS",
                    DecisionReason.APPROVAL,
                    m.getLatePaymentCount() + " late/overdue payment signals"
            ));
        }

        if (cfg.isEnablePaymentFailureRule()
                && m.getPaymentFailuresLast90Days() >= cfg.getPaymentFailureCountThreshold()) {
            reasons.add(new DecisionReason(
                    "PAYMENT_FAILURE_HISTORY",
                    DecisionReason.APPROVAL,
                    m.getPaymentFailuresLast90Days() + " payment failures in window"
            ));
        }

        if (cfg.isEnablePaymentPromiseRule() && m.getBrokenPaymentPromises() > 0) {
            String severity = m.getBrokenPaymentPromises() >= 2
                    ? DecisionReason.PAYMENT_REQUIRED
                    : DecisionReason.APPROVAL;
            reasons.add(new DecisionReason(
                    "PAYMENT_PROMISE_BROKEN",
                    severity,
                    m.getBrokenPaymentPromises() + " broken payment promise(s) — "
                            + (m.getBrokenPaymentPromises() >= 2 ? "collect before new orders" : "manager review")
            ));
        }

        if (cfg.isEnableLowMarginWithOverdueRule()
                && m.getCustomerMarginPercent() != null
                && m.getOverdueAmount() > 0
                && m.getCustomerMarginPercent() < cfg.getLowMarginPercentThreshold()) {
            reasons.add(new DecisionReason(
                    "LOW_MARGIN_WITH_OVERDUE",
                    DecisionReason.APPROVAL,
                    String.format("Average margin %.1f%% with ₹%s overdue — tighten credit",
                            m.getCustomerMarginPercent(), fmt(m.getOverdueAmount()))
            ));
        }

        if (m.getCreditUtilization() != null
                && m.getCreditUtilization() >= cfg.getCreditUtilizationHighPercent()) {
            reasons.add(new DecisionReason(
                    "HIGH_CREDIT_UTILIZATION",
                    DecisionReason.APPROVAL,
                    String.format("Credit utilization %.1f%%", m.getCreditUtilization())
            ));
        }

        double avg = m.getAverageOrderValueLast90Days() > 0
                ? m.getAverageOrderValueLast90Days()
                : m.getAverageOrderValue();
        if (avg > 0 && orderAmount >= avg * cfg.getUnusualOrderMultiplier()) {
            reasons.add(new DecisionReason(
                    "ORDER_AMOUNT_SIGNIFICANTLY_ABOVE_HISTORY",
                    DecisionReason.APPROVAL,
                    "Order ₹" + fmt(orderAmount) + " vs average ₹" + fmt(avg)
            ));
        }

        for (String alert : m.getAlerts()) {
            reasons.add(new DecisionReason(alert, DecisionReason.RISK_SIGNAL, alert));
        }

        return reasons;
    }

    public String resolveOrderDecision(List<DecisionReason> reasons) {
        boolean manualHold = reasons.stream().anyMatch(r -> "MANUAL_HOLD".equals(r.code()));
        if (manualHold) {
            return OrderDecisionResult.MANUAL_HOLD;
        }
        boolean hard = reasons.stream().anyMatch(r -> DecisionReason.HARD_BLOCK.equals(r.severity()));
        if (hard) {
            return OrderDecisionResult.DO_NOT_TAKE_ORDER;
        }
        boolean pay = reasons.stream().anyMatch(r -> DecisionReason.PAYMENT_REQUIRED.equals(r.severity()));
        if (pay) {
            return OrderDecisionResult.TAKE_ORDER_AFTER_PAYMENT;
        }
        boolean approval = reasons.stream().anyMatch(r -> DecisionReason.APPROVAL.equals(r.severity()));
        if (approval) {
            return OrderDecisionResult.TAKE_ORDER_WITH_APPROVAL;
        }
        return OrderDecisionResult.TAKE_ORDER;
    }

    private static String fmt(double v) {
        return String.format("%,.0f", v);
    }
}
