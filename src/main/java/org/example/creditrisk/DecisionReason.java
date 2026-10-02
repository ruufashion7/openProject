package org.example.creditrisk;

public record DecisionReason(
        String code,
        String severity,
        String message
) {
    public static final String HARD_BLOCK = "HARD_BLOCK";
    public static final String APPROVAL = "APPROVAL";
    public static final String PAYMENT_REQUIRED = "PAYMENT_REQUIRED";
    public static final String RISK_SIGNAL = "RISK_SIGNAL";
}
