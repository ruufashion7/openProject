package org.example.creditrisk;

import java.util.ArrayList;
import java.util.List;

public record CustomerRiskScore(
        double paymentScore,
        double businessScore,
        double riskScore,
        String riskCategory,
        String paymentBehaviour,
        String businessValue,
        List<ScoreComponent> paymentComponents,
        List<ScoreComponent> businessComponents
) {
    public CustomerRiskScore {
        if (paymentComponents == null) {
            paymentComponents = List.of();
        }
        if (businessComponents == null) {
            businessComponents = List.of();
        }
        paymentScore = Math.min(60, Math.max(0, paymentScore));
        businessScore = Math.min(40, Math.max(0, businessScore));
        riskScore = Math.min(100, Math.max(0, paymentScore + businessScore));
    }

    public static CustomerRiskScore of(
            double paymentScore,
            double businessScore,
            String riskCategory,
            String paymentBehaviour,
            String businessValue,
            List<ScoreComponent> paymentComponents,
            List<ScoreComponent> businessComponents
    ) {
        double p = Math.min(60, Math.max(0, paymentScore));
        double b = Math.min(40, Math.max(0, businessScore));
        return new CustomerRiskScore(
                p,
                b,
                p + b,
                riskCategory,
                paymentBehaviour,
                businessValue,
                paymentComponents != null ? new ArrayList<>(paymentComponents) : List.of(),
                businessComponents != null ? new ArrayList<>(businessComponents) : List.of()
        );
    }
}
