package org.example.creditrisk;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PaymentDecisionService {

    public record PaymentOutcome(String paymentDecision, double requiredPayment) {
    }

    public PaymentOutcome decide(
            CustomerRiskMetrics m,
            String orderDecision,
            double orderAmount,
            double reservedExposure,
            List<DecisionReason> reasons,
            CreditRiskEngineConfigDocument cfg
    ) {
        Double limit = m.getEffectiveCreditLimit();
        double outstanding = m.getCurrentOutstanding();
        double projected = outstanding + orderAmount + reservedExposure;
        double required = 0;
        if (limit != null) {
            required = Math.max(0, projected - limit);
        }

        boolean hardBlock = OrderDecisionResult.DO_NOT_TAKE_ORDER.equals(orderDecision)
                || OrderDecisionResult.MANUAL_HOLD.equals(orderDecision);
        boolean severeOverdue = m.getMaximumOverdueDays() > cfg.getOverdueHardBlockDays()
                || reasons.stream().anyMatch(r -> "INVOICE_OVERDUE_120_DAYS".equals(r.code()));

        if (hardBlock || severeOverdue || "C".equalsIgnoreCase(m.getCustomerCategory())) {
            return new PaymentOutcome(OrderDecisionResult.FULL_ADVANCE, Math.max(required, orderAmount));
        }

        if (required > 0.01 || OrderDecisionResult.TAKE_ORDER_AFTER_PAYMENT.equals(orderDecision)) {
            return new PaymentOutcome(OrderDecisionResult.PARTIAL_ADVANCE, required > 0 ? required : orderAmount);
        }

        return new PaymentOutcome(OrderDecisionResult.CREDIT, 0);
    }
}
