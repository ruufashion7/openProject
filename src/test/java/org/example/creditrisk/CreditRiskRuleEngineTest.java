package org.example.creditrisk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreditRiskRuleEngineTest {

    private CreditRiskRuleEngine engine;
    private PaymentDecisionService paymentDecisionService;
    private CreditRiskEngineConfigDocument cfg;
    private CustomerRiskScore neutralScore;

    @BeforeEach
    void setUp() {
        engine = new CreditRiskRuleEngine();
        paymentDecisionService = new PaymentDecisionService();
        cfg = new CreditRiskEngineConfigDocument();
        neutralScore = CustomerRiskScore.of(50, 30, "RELIABLE", "GOOD", "MEDIUM", List.of(), List.of());
    }

    @Test
    void normalCustomer_takeOrderCredit() {
        CustomerRiskMetrics m = base();
        m.setEffectiveCreditLimit(1_000_000.0);
        m.setCurrentOutstanding(200_000);
        List<DecisionReason> reasons = engine.evaluate(m, neutralScore, 100_000, 0, cfg);
        String decision = engine.resolveOrderDecision(reasons);
        assertEquals(OrderDecisionResult.TAKE_ORDER, decision);
        var pay = paymentDecisionService.decide(m, decision, 100_000, 0, reasons, cfg);
        assertEquals(OrderDecisionResult.CREDIT, pay.paymentDecision());
    }

    @Test
    void categoryC_doNotTakeOrder() {
        CustomerRiskMetrics m = base();
        m.setCustomerCategory("C");
        m.setEffectiveCreditLimit(0.0);
        List<DecisionReason> reasons = engine.evaluate(m, neutralScore, 50_000, 0, cfg);
        assertTrue(reasons.stream().anyMatch(r -> "CUSTOMER_STATUS_C".equals(r.code())));
        assertEquals(OrderDecisionResult.DO_NOT_TAKE_ORDER, engine.resolveOrderDecision(reasons));
    }

    @Test
    void manualHold_manualHoldDecision() {
        CustomerRiskMetrics m = base();
        m.setManualHold(true);
        m.setManualHoldReason("Finance dispute");
        List<DecisionReason> reasons = engine.evaluate(m, neutralScore, 10_000, 0, cfg);
        assertEquals(OrderDecisionResult.MANUAL_HOLD, engine.resolveOrderDecision(reasons));
    }

    @Test
    void overdueCategoryA_89_noHardBlock() {
        // A threshold = 90
        CustomerRiskMetrics m = base();
        m.setCustomerCategory("A");
        m.setMaximumOverdueDays(89);
        m.setOverdueAmount(50_000);
        m.setEffectiveCreditLimit(1_000_000.0);
        List<DecisionReason> reasons = engine.evaluate(m, neutralScore, 10_000, 0, cfg);
        assertFalse(reasons.stream().anyMatch(r -> "INVOICE_OVERDUE_HARD_BLOCK".equals(r.code())));
    }

    @Test
    void overdueCategoryA_90_boundary_noBlockAtExactlyThreshold() {
        CustomerRiskMetrics m = base();
        m.setCustomerCategory("A");
        m.setMaximumOverdueDays(90);
        m.setEffectiveCreditLimit(1_000_000.0);
        List<DecisionReason> reasons = engine.evaluate(m, neutralScore, 10_000, 0, cfg);
        assertFalse(reasons.stream().anyMatch(r -> "INVOICE_OVERDUE_HARD_BLOCK".equals(r.code())));
    }

    @Test
    void overdueCategoryA_91_hardBlock() {
        CustomerRiskMetrics m = base();
        m.setCustomerCategory("A");
        m.setMaximumOverdueDays(91);
        m.setOverdueAmount(200_000);
        m.setEffectiveCreditLimit(1_000_000.0);
        List<DecisionReason> reasons = engine.evaluate(m, neutralScore, 10_000, 0, cfg);
        assertTrue(reasons.stream().anyMatch(r -> "INVOICE_OVERDUE_HARD_BLOCK".equals(r.code())));
        assertEquals(OrderDecisionResult.DO_NOT_TAKE_ORDER, engine.resolveOrderDecision(reasons));
    }

    @Test
    void overdueCategoryB_61_hardBlock() {
        CustomerRiskMetrics m = base();
        m.setCustomerCategory("B");
        m.setMaximumOverdueDays(61);
        m.setEffectiveCreditLimit(1_000_000.0);
        List<DecisionReason> reasons = engine.evaluate(m, neutralScore, 10_000, 0, cfg);
        assertTrue(reasons.stream().anyMatch(r -> "INVOICE_OVERDUE_HARD_BLOCK".equals(r.code())));
    }

    @Test
    void overdueSemiWholesale_119_noBlock() {
        CustomerRiskMetrics m = base();
        m.setCustomerCategory("semi-wholesale");
        m.setMaximumOverdueDays(119);
        m.setEffectiveCreditLimit(1_000_000.0);
        List<DecisionReason> reasons = engine.evaluate(m, neutralScore, 10_000, 0, cfg);
        assertFalse(reasons.stream().anyMatch(r -> "INVOICE_OVERDUE_HARD_BLOCK".equals(r.code())));
    }

    @Test
    void noPaymentAgainstDue_whenEnabled() {
        cfg.setEnableNoPaymentAgainstDueRule(true);
        CustomerRiskMetrics m = base();
        m.setOverdueAmount(100_000);
        m.setDaysSincePaymentAgainstDue(45);
        m.setEffectiveCreditLimit(1_000_000.0);
        List<DecisionReason> reasons = engine.evaluate(m, neutralScore, 10_000, 0, cfg);
        assertTrue(reasons.stream().anyMatch(r -> "NO_PAYMENT_AGAINST_DUE_INVOICES_30_DAYS".equals(r.code())));
    }

    @Test
    void noPayment_butInvoicesNotDue_doesNotBlockWhenDisabled() {
        cfg.setEnableNoPaymentAgainstDueRule(false);
        CustomerRiskMetrics m = base();
        m.setOverdueAmount(0);
        m.setDaysSincePaymentAgainstDue(45);
        m.setEffectiveCreditLimit(1_000_000.0);
        List<DecisionReason> reasons = engine.evaluate(m, neutralScore, 10_000, 0, cfg);
        assertFalse(reasons.stream().anyMatch(r -> "NO_PAYMENT_AGAINST_DUE_INVOICES_30_DAYS".equals(r.code())));
    }

    @Test
    void creditLimitBreach_softForReliable() {
        // default BY_RISK_CATEGORY + RELIABLE score → payment required
        CustomerRiskMetrics m = base();
        m.setEffectiveCreditLimit(1_000_000.0);
        m.setCurrentOutstanding(800_000);
        List<DecisionReason> reasons = engine.evaluate(m, neutralScore, 400_000, 0, cfg);
        assertEquals(OrderDecisionResult.TAKE_ORDER_AFTER_PAYMENT, engine.resolveOrderDecision(reasons));
        var pay = paymentDecisionService.decide(m, OrderDecisionResult.TAKE_ORDER_AFTER_PAYMENT, 400_000, 0, reasons, cfg);
        assertEquals(OrderDecisionResult.PARTIAL_ADVANCE, pay.paymentDecision());
        assertEquals(200_000, pay.requiredPayment(), 0.01);
    }

    @Test
    void creditLimitBreach_hardForRisky() {
        CustomerRiskScore risky = CustomerRiskScore.of(30, 20, "RISKY", "POOR", "LOW", List.of(), List.of());
        CustomerRiskMetrics m = base();
        m.setEffectiveCreditLimit(1_000_000.0);
        m.setCurrentOutstanding(800_000);
        List<DecisionReason> reasons = engine.evaluate(m, risky, 400_000, 0, cfg);
        assertEquals(OrderDecisionResult.DO_NOT_TAKE_ORDER, engine.resolveOrderDecision(reasons));
    }

    @Test
    void creditLimitBreach_hardBlockMode() {
        cfg.setCreditLimitBreachMode("HARD_BLOCK");
        CustomerRiskMetrics m = base();
        m.setEffectiveCreditLimit(100_000.0);
        m.setCurrentOutstanding(150_000);
        List<DecisionReason> reasons = engine.evaluate(m, neutralScore, 10_000, 0, cfg);
        assertEquals(OrderDecisionResult.DO_NOT_TAKE_ORDER, engine.resolveOrderDecision(reasons));
    }

    @Test
    void newCustomer_threeInvoicesZeroPayments_block() {
        CustomerRiskMetrics m = base();
        m.setCustomerTenureDays(45);
        m.setTotalInvoices(4);
        m.setSuccessfulPaymentCount(0);
        m.setEffectiveCreditLimit(100_000.0);
        List<DecisionReason> reasons = engine.evaluate(m, neutralScore, 10_000, 0, cfg);
        assertTrue(reasons.stream().anyMatch(r -> "NEW_CUSTOMER_NO_PAYMENT_HISTORY".equals(r.code())));
        assertEquals(OrderDecisionResult.DO_NOT_TAKE_ORDER, engine.resolveOrderDecision(reasons));
    }

    @Test
    void newCustomer_twoInvoices_noTrigger() {
        CustomerRiskMetrics m = base();
        m.setCustomerTenureDays(45);
        m.setTotalInvoices(2);
        m.setSuccessfulPaymentCount(0);
        m.setEffectiveCreditLimit(100_000.0);
        List<DecisionReason> reasons = engine.evaluate(m, neutralScore, 10_000, 0, cfg);
        assertFalse(reasons.stream().anyMatch(r -> "NEW_CUSTOMER_NO_PAYMENT_HISTORY".equals(r.code())));
    }

    @Test
    void newCustomer_threeInvoicesOnePayment_noTrigger() {
        CustomerRiskMetrics m = base();
        m.setCustomerTenureDays(45);
        m.setTotalInvoices(3);
        m.setSuccessfulPaymentCount(1);
        m.setEffectiveCreditLimit(100_000.0);
        List<DecisionReason> reasons = engine.evaluate(m, neutralScore, 10_000, 0, cfg);
        assertFalse(reasons.stream().anyMatch(r -> "NEW_CUSTOMER_NO_PAYMENT_HISTORY".equals(r.code())));
    }

    @Test
    void multipleOverdue_approval() {
        CustomerRiskMetrics m = base();
        m.setOverdueInvoiceCount(5);
        m.setEffectiveCreditLimit(1_000_000.0);
        List<DecisionReason> reasons = engine.evaluate(m, neutralScore, 10_000, 0, cfg);
        assertTrue(reasons.stream().anyMatch(r -> "MULTIPLE_OVERDUE_INVOICES".equals(r.code())));
        assertEquals(OrderDecisionResult.TAKE_ORDER_WITH_APPROVAL, engine.resolveOrderDecision(reasons));
    }

    @Test
    void unusualOrder_approval() {
        CustomerRiskMetrics m = base();
        m.setAverageOrderValue(50_000);
        m.setAverageOrderValueLast90Days(50_000);
        m.setEffectiveCreditLimit(5_000_000.0);
        List<DecisionReason> reasons = engine.evaluate(m, neutralScore, 800_000, 0, cfg);
        assertTrue(reasons.stream().anyMatch(r -> "ORDER_AMOUNT_SIGNIFICANTLY_ABOVE_HISTORY".equals(r.code())));
    }

    @Test
    void multipleRules_allReturned() {
        CustomerRiskMetrics m = base();
        m.setCustomerCategory("C");
        m.setMaximumOverdueDays(137);
        m.setEffectiveCreditLimit(1_000_000.0);
        m.setCurrentOutstanding(800_000);
        List<DecisionReason> reasons = engine.evaluate(m, neutralScore, 300_000, 0, cfg);
        assertTrue(reasons.size() >= 2);
        assertTrue(reasons.stream().anyMatch(r -> "CUSTOMER_STATUS_C".equals(r.code())));
        assertTrue(reasons.stream().anyMatch(r -> "INVOICE_OVERDUE_HARD_BLOCK".equals(r.code())));
    }

    @Test
    void reservedExposure_countsTowardLimit() {
        CustomerRiskMetrics m = base();
        m.setEffectiveCreditLimit(1_000_000.0);
        m.setCurrentOutstanding(800_000);
        // without reservation: 800k+100k=900k OK
        assertEquals(OrderDecisionResult.TAKE_ORDER,
                engine.resolveOrderDecision(engine.evaluate(m, neutralScore, 100_000, 0, cfg)));
        // with 150k reserved: 800+100+150=1050k → payment required (RELIABLE = soft)
        assertEquals(OrderDecisionResult.TAKE_ORDER_AFTER_PAYMENT,
                engine.resolveOrderDecision(engine.evaluate(m, neutralScore, 100_000, 150_000, cfg)));
    }

    private static CustomerRiskMetrics base() {
        CustomerRiskMetrics m = new CustomerRiskMetrics();
        m.setCustomerKey("abc traders");
        m.setCustomerName("ABC Traders");
        m.setCustomerCategory("A");
        m.setPaidInvoices(10);
        m.setOnTimePaymentPercentage(90);
        return m;
    }
}
