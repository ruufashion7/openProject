package org.example.creditrisk;

public record CreditRiskCollectTodayItem(
        String customerKey,
        String customerName,
        String phoneNumber,
        double overdueAmount,
        String actionBucket,
        String nextPaymentDate,
        int riskScore,
        String riskCategory
) {
}
