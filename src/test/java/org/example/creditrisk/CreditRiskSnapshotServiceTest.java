package org.example.creditrisk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreditRiskSnapshotServiceTest {

    @Test
    void classifyBucket_blockedForDoNotTake() {
        assertEquals(
                CreditRiskSnapshotService.BUCKET_BLOCKED,
                CreditRiskSnapshotService.classifyBucket(OrderDecisionResult.DO_NOT_TAKE_ORDER, "RELIABLE")
        );
    }

    @Test
    void classifyBucket_needPayment() {
        assertEquals(
                CreditRiskSnapshotService.BUCKET_NEED_PAYMENT,
                CreditRiskSnapshotService.classifyBucket(OrderDecisionResult.TAKE_ORDER_AFTER_PAYMENT, "RELIABLE")
        );
    }

    @Test
    void classifyBucket_watchFromRiskWhenClearDecisionAndOverdue() {
        CustomerRiskMetrics m = new CustomerRiskMetrics();
        m.setOverdueAmount(10_000);
        assertEquals(
                CreditRiskSnapshotService.BUCKET_WATCH,
                CreditRiskSnapshotService.classifyBucket(
                        OrderDecisionResult.TAKE_ORDER, "WATCH", null, m)
        );
    }

    @Test
    void classifyBucket_clearWhenTakeOrderAndNoOverdueDespiteWatchScore() {
        CustomerRiskMetrics m = new CustomerRiskMetrics();
        m.setOverdueAmount(0);
        m.setCurrentOutstanding(500_000);
        assertEquals(
                CreditRiskSnapshotService.BUCKET_CLEAR,
                CreditRiskSnapshotService.classifyBucket(
                        OrderDecisionResult.TAKE_ORDER, "WATCH", null, m)
        );
    }

    @Test
    void growSales_eligibleForReliableWithApprovalNotBlocked() {
        CreditRiskSnapshotDocument s = new CreditRiskSnapshotDocument();
        s.setManualHold(false);
        s.setActionBucket(CreditRiskSnapshotService.BUCKET_NEED_APPROVAL);
        s.setSuggestedOrderDecision(OrderDecisionResult.TAKE_ORDER_WITH_APPROVAL);
        s.setRiskCategory("RELIABLE");
        assertTrue(CreditRiskSnapshotService.isGrowSalesEligible(s));
    }

    @Test
    void growSales_notEligibleWhenPayFirst() {
        CreditRiskSnapshotDocument s = new CreditRiskSnapshotDocument();
        s.setActionBucket(CreditRiskSnapshotService.BUCKET_NEED_PAYMENT);
        s.setSuggestedOrderDecision(OrderDecisionResult.TAKE_ORDER_AFTER_PAYMENT);
        s.setRiskCategory("RELIABLE");
        assertFalse(CreditRiskSnapshotService.isGrowSalesEligible(s));
    }

    @Test
    void softLimit_reliableYes_riskyNo() {
        CreditRiskEngineConfigDocument cfg = new CreditRiskEngineConfigDocument();
        assertTrue(cfg.isSoftCreditLimitBreach("RELIABLE"));
        assertTrue(cfg.isSoftCreditLimitBreach("VERY_RELIABLE"));
        assertFalse(cfg.isSoftCreditLimitBreach("RISKY"));
        assertFalse(cfg.isSoftCreditLimitBreach("WATCH"));
    }

    @Test
    void nextPaymentDatePastWithOutstanding_isBrokenPromise() {
        assertEquals(1, CreditPaymentPromiseService.brokenFromNextPaymentDate("01-Jan-2020", 100));
        assertEquals(0, CreditPaymentPromiseService.brokenFromNextPaymentDate("01-Jan-2020", 0));
        assertEquals(0, CreditPaymentPromiseService.brokenFromNextPaymentDate(null, 100));
        assertEquals(0, CreditPaymentPromiseService.brokenFromNextPaymentDate("01-Jan-2099", 100));
    }

    @Test
    void resolvePromiseDate_fromDaysUntil() {
        String d = CreditPaymentPromiseService.resolvePromiseDate(null, 10);
        assertTrue(d != null && !d.isBlank());
        assertEquals("15-Aug-2026", CreditPaymentPromiseService.resolvePromiseDate("15-Aug-2026", 10));
    }

    @Test
    void classifyBucket_collectCashGoesNeedPayment() {
        assertEquals(
                CreditRiskSnapshotService.BUCKET_NEED_PAYMENT,
                CreditRiskSnapshotService.classifyBucket(
                        OrderDecisionResult.TAKE_ORDER,
                        "RELIABLE",
                        java.util.List.of("COLLECT_CASH_DUE")
                )
        );
    }
}
