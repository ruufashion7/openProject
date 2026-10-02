package org.example.creditrisk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomerRiskScoringServiceTest {

    private CustomerRiskScoringService scoring;
    private CreditRiskEngineConfigDocument cfg;

    @BeforeEach
    void setUp() {
        scoring = new CustomerRiskScoringService();
        cfg = new CreditRiskEngineConfigDocument();
    }

    @Test
    void score_capsAt60And40And100() {
        CustomerRiskMetrics m = excellentMetrics();
        CustomerRiskScore score = scoring.score(m, cfg);
        assertTrue(score.paymentScore() <= 60);
        assertTrue(score.businessScore() <= 40);
        assertTrue(score.riskScore() <= 100);
        assertEquals(score.paymentScore() + score.businessScore(), score.riskScore(), 0.01);
    }

    @Test
    void score_veryReliable_whenExcellent() {
        CustomerRiskScore score = scoring.score(excellentMetrics(), cfg);
        assertTrue(score.riskScore() >= 85);
        assertEquals("VERY_RELIABLE", score.riskCategory());
    }

    @Test
    void score_highRisk_whenPoor() {
        CustomerRiskMetrics m = new CustomerRiskMetrics();
        m.setOnTimePaymentPercentage(20);
        m.setPaidInvoices(10);
        m.setAveragePaymentDelayDays(60);
        m.setOverdueAmount(1_000_000);
        m.setCreditUtilization(120.0);
        m.setPaymentFailuresLast90Days(5);
        m.setMaximumOverdueDays(200);
        m.setOrderFrequencyPerMonth(0);
        m.setSalesLast90Days(0);
        m.setCustomerTenureDays(10);
        m.setReturnCancellationCount(20);
        CustomerRiskScore score = scoring.score(m, cfg);
        assertTrue(score.riskScore() < 40);
        assertEquals("HIGH_RISK", score.riskCategory());
    }

    @Test
    void categories_mapToBreakpoints() {
        assertEquals("VERY_RELIABLE", CustomerRiskScoringService.riskCategory(90, cfg));
        assertEquals("RELIABLE", CustomerRiskScoringService.riskCategory(75, cfg));
        assertEquals("WATCH", CustomerRiskScoringService.riskCategory(60, cfg));
        assertEquals("RISKY", CustomerRiskScoringService.riskCategory(45, cfg));
        assertEquals("HIGH_RISK", CustomerRiskScoringService.riskCategory(30, cfg));
    }

    @Test
    void paymentComponents_present() {
        CustomerRiskScore score = scoring.score(excellentMetrics(), cfg);
        assertEquals(6, score.paymentComponents().size());
        assertEquals(5, score.businessComponents().size());
        double pMax = score.paymentComponents().stream().mapToDouble(ScoreComponent::maximumScore).sum();
        double bMax = score.businessComponents().stream().mapToDouble(ScoreComponent::maximumScore).sum();
        assertEquals(60, pMax, 0.01);
        assertEquals(40, bMax, 0.01);
    }

    private static CustomerRiskMetrics excellentMetrics() {
        CustomerRiskMetrics m = new CustomerRiskMetrics();
        m.setOnTimePaymentPercentage(98);
        m.setPaidInvoices(50);
        m.setAveragePaymentDelayDays(0);
        m.setOverdueAmount(0);
        m.setCreditUtilization(40.0);
        m.setPaymentFailuresLast90Days(0);
        m.setMaximumOverdueDays(0);
        m.setOrderFrequencyPerMonth(5);
        m.setSalesLast90Days(2_000_000);
        m.setLifetimeSales(20_000_000);
        m.setCustomerTenureDays(900);
        m.setReturnCancellationCount(0);
        m.setCustomerMarginPercent(25.0);
        return m;
    }
}
