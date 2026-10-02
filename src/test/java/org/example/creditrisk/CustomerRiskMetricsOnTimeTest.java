package org.example.creditrisk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CustomerRiskMetricsOnTimeTest {

    @Test
    void onTimeByOutstanding_notInflatedByOldPaidHistory() {
        // Many paid lines, few open overdue — old formula gave ~90%; amount view uses outstanding.
        double pct = CustomerRiskMetricsService.computeOnTimePaymentPercentage(
                500_000, 240_796, 500, 3);
        assertEquals(51.84, pct, 0.5);
    }

    @Test
    void onTimeWhenNoOutstanding_fallsBackToLineCounts() {
        double pct = CustomerRiskMetricsService.computeOnTimePaymentPercentage(0, 0, 80, 20);
        assertEquals(80.0, pct, 0.01);
    }
}
