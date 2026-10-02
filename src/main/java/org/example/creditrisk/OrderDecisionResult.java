package org.example.creditrisk;

import java.util.List;

public record OrderDecisionResult(
        String customerKey,
        String customerName,
        double orderAmount,
        String orderDecision,
        String paymentDecision,
        Double creditLimit,
        double currentOutstanding,
        double overdueAmount,
        Double creditUtilization,
        double projectedExposure,
        double requiredPayment,
        double reservedExposure,
        double paymentScore,
        double businessScore,
        double riskScore,
        String riskCategory,
        String paymentBehaviour,
        String businessValue,
        Double recommendedCreditLimit,
        List<DecisionReason> reasons,
        List<ScoreComponent> paymentComponents,
        List<ScoreComponent> businessComponents,
        List<String> dataQualityWarnings,
        List<String> alerts,
        String auditId
) {
    public static final String TAKE_ORDER = "TAKE_ORDER";
    public static final String TAKE_ORDER_WITH_APPROVAL = "TAKE_ORDER_WITH_APPROVAL";
    public static final String TAKE_ORDER_AFTER_PAYMENT = "TAKE_ORDER_AFTER_PAYMENT";
    public static final String DO_NOT_TAKE_ORDER = "DO_NOT_TAKE_ORDER";
    public static final String MANUAL_HOLD = "MANUAL_HOLD";

    public static final String CREDIT = "CREDIT";
    public static final String PARTIAL_ADVANCE = "PARTIAL_ADVANCE";
    public static final String FULL_ADVANCE = "FULL_ADVANCE";

    public OrderDecisionResult {
        if (reasons == null) {
            reasons = List.of();
        }
        if (paymentComponents == null) {
            paymentComponents = List.of();
        }
        if (businessComponents == null) {
            businessComponents = List.of();
        }
        if (dataQualityWarnings == null) {
            dataQualityWarnings = List.of();
        }
        if (alerts == null) {
            alerts = List.of();
        }
    }
}
