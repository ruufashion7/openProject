package org.example.creditrisk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CreditRiskStandingProbeTest {

    @Test
    void probeUsesNinetyDayAverageFirst() {
        CustomerRiskMetrics m = new CustomerRiskMetrics();
        m.setAverageOrderValue(10_000);
        m.setAverageOrderValueLast90Days(25_000);
        assertEquals(25_000, CreditRiskService.standingProbeOrderAmount(m), 0.01);
    }

    @Test
    void probeFallsBackToLifetimeAverage() {
        CustomerRiskMetrics m = new CustomerRiskMetrics();
        m.setAverageOrderValue(15_000);
        assertEquals(15_000, CreditRiskService.standingProbeOrderAmount(m), 0.01);
    }
}
