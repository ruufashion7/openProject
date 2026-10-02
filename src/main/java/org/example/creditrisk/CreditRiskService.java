package org.example.creditrisk;

import org.example.payment.PaymentDateOverride;
import org.example.payment.PaymentDateOverrideCopy;
import org.example.payment.PaymentDateOverrideRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class CreditRiskService {

    private static final Logger logger = LoggerFactory.getLogger(CreditRiskService.class);

    private final CustomerRiskMetricsService metricsService;
    private final CustomerRiskScoringService scoringService;
    private final CreditRiskRuleEngine ruleEngine;
    private final PaymentDecisionService paymentDecisionService;
    private final CreditRiskEngineConfigService configService;
    private final CreditExposureReservationService reservationService;
    private final CreditDecisionAuditService auditService;
    private final CreditRiskSnapshotService snapshotService;
    private final PaymentDateOverrideRepository paymentDateOverrideRepository;
    private final CreditPaymentEventService paymentEventService;
    private final CreditPaymentPromiseService promiseService;
    private final CreditRiskInsightsService insightsService;
    private final CreditLimitAuditRepository creditLimitAuditRepository;

    public CreditRiskService(
            CustomerRiskMetricsService metricsService,
            CustomerRiskScoringService scoringService,
            CreditRiskRuleEngine ruleEngine,
            PaymentDecisionService paymentDecisionService,
            CreditRiskEngineConfigService configService,
            CreditExposureReservationService reservationService,
            CreditDecisionAuditService auditService,
            CreditRiskSnapshotService snapshotService,
            PaymentDateOverrideRepository paymentDateOverrideRepository,
            CreditPaymentEventService paymentEventService,
            CreditPaymentPromiseService promiseService,
            CreditRiskInsightsService insightsService,
            CreditLimitAuditRepository creditLimitAuditRepository
    ) {
        this.metricsService = metricsService;
        this.scoringService = scoringService;
        this.ruleEngine = ruleEngine;
        this.paymentDecisionService = paymentDecisionService;
        this.configService = configService;
        this.reservationService = reservationService;
        this.auditService = auditService;
        this.snapshotService = snapshotService;
        this.paymentDateOverrideRepository = paymentDateOverrideRepository;
        this.paymentEventService = paymentEventService;
        this.promiseService = promiseService;
        this.insightsService = insightsService;
        this.creditLimitAuditRepository = creditLimitAuditRepository;
    }

    public Optional<CustomerRiskSummaryResponse> summary(String customerOrPhone) {
        Optional<CustomerRiskMetrics> opt = metricsService.buildForCustomer(customerOrPhone);
        if (opt.isEmpty()) {
            return Optional.empty();
        }
        CustomerRiskMetrics m = opt.get();
        CreditRiskEngineConfigDocument cfg = configService.getOrCreate();
        CustomerRiskScore score = scoringService.score(m, cfg);
        Double recommended = scoringService.recommendCreditLimit(m, score);
        String decision = standingDecision(m, score, cfg);
        String bucket = CreditRiskSnapshotService.classifyBucket(decision, score.riskCategory(), m.getAlerts(), m);
        snapshotService.upsert(m, score, recommended, decision, bucket);
        return Optional.of(CustomerRiskSummaryResponse.from(m, score, recommended));
    }

    public OrderDecisionResult evaluate(
            String customerOrPhone,
            double orderAmount,
            String referenceId,
            boolean reserveExposure,
            String decidedBy
    ) {
        CustomerRiskMetrics m = metricsService.buildForCustomer(customerOrPhone)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found"));
        CreditRiskEngineConfigDocument cfg = configService.getOrCreate();
        CustomerRiskScore score = scoringService.score(m, cfg);
        Double recommended = scoringService.recommendCreditLimit(m, score);

        double reserved = reservationService.activeReserved(m.getCustomerKey());
        if (reserveExposure && referenceId != null && !referenceId.isBlank()) {
            reserved = reservationService.reserve(m.getCustomerKey(), referenceId, orderAmount);
            // reserved includes this order — for projected exposure use other reservations only
            reserved = Math.max(0, reserved - orderAmount);
        }

        List<DecisionReason> reasons = ruleEngine.evaluate(m, score, orderAmount, reserved, cfg);
        String orderDecision = ruleEngine.resolveOrderDecision(reasons);
        PaymentDecisionService.PaymentOutcome pay =
                paymentDecisionService.decide(m, orderDecision, orderAmount, reserved, reasons, cfg);

        double projected = m.getCurrentOutstanding() + orderAmount + reserved;
        OrderDecisionResult result = new OrderDecisionResult(
                m.getCustomerKey(),
                m.getCustomerName(),
                orderAmount,
                orderDecision,
                pay.paymentDecision(),
                m.getEffectiveCreditLimit(),
                m.getCurrentOutstanding(),
                m.getOverdueAmount(),
                m.getCreditUtilization(),
                projected,
                pay.requiredPayment(),
                reserved,
                score.paymentScore(),
                score.businessScore(),
                score.riskScore(),
                score.riskCategory(),
                score.paymentBehaviour(),
                score.businessValue(),
                recommended,
                reasons,
                score.paymentComponents(),
                score.businessComponents(),
                m.getDataQualityWarnings(),
                m.getAlerts(),
                null
        );
        CreditDecisionAuditDocument audit = auditService.record(result, decidedBy, referenceId);
        String bucket = CreditRiskSnapshotService.classifyBucket(orderDecision, score.riskCategory(), m.getAlerts(), m);
        snapshotService.upsert(m, score, recommended, orderDecision, bucket);
        return new OrderDecisionResult(
                result.customerKey(),
                result.customerName(),
                result.orderAmount(),
                result.orderDecision(),
                result.paymentDecision(),
                result.creditLimit(),
                result.currentOutstanding(),
                result.overdueAmount(),
                result.creditUtilization(),
                result.projectedExposure(),
                result.requiredPayment(),
                result.reservedExposure(),
                result.paymentScore(),
                result.businessScore(),
                result.riskScore(),
                result.riskCategory(),
                result.paymentBehaviour(),
                result.businessValue(),
                result.recommendedCreditLimit(),
                result.reasons(),
                result.paymentComponents(),
                result.businessComponents(),
                result.dataQualityWarnings(),
                result.alerts(),
                audit.getId()
        );
    }

    /**
     * Rebuild snapshots for all active customer_master rows (post-upload).
     * ponytail: sync full scan; move off request thread if upload latency exceeds ~a few seconds.
     */
    public Map<String, Object> rebuildSnapshots() {
        CreditRiskEngineConfigDocument cfg = configService.getOrCreate();
        int ok = 0;
        int failed = 0;
        for (PaymentDateOverride o : paymentDateOverrideRepository.findAll()) {
            if (o == null || o.isExcluded() || !o.isActive()) {
                continue;
            }
            String key = o.customerName() != null && !o.customerName().isBlank()
                    ? o.customerName()
                    : o.customerKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            try {
                Optional<CustomerRiskMetrics> opt = metricsService.buildForCustomer(key);
                if (opt.isEmpty()) {
                    failed++;
                    continue;
                }
                CustomerRiskMetrics m = opt.get();
                CustomerRiskScore score = scoringService.score(m, cfg);
                Double recommended = scoringService.recommendCreditLimit(m, score);
                String decision = standingDecision(m, score, cfg);
                String bucket = CreditRiskSnapshotService.classifyBucket(decision, score.riskCategory(), m.getAlerts(), m);
                snapshotService.upsert(m, score, recommended, decision, bucket);
                ok++;
            } catch (RuntimeException ex) {
                failed++;
                logger.warn("Credit risk snapshot rebuild failed for {}: {}", key, ex.getMessage());
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rebuilt", ok);
        result.put("failed", failed);
        return result;
    }

    private String standingDecision(
            CustomerRiskMetrics m,
            CustomerRiskScore score,
            CreditRiskEngineConfigDocument cfg
    ) {
        double reserved = reservationService.activeReserved(m.getCustomerKey());
        double probeOrder = standingProbeOrderAmount(m);
        List<DecisionReason> reasons = ruleEngine.evaluate(m, score, probeOrder, reserved, cfg);
        return ruleEngine.resolveOrderDecision(reasons);
    }

    /** Typical next order size for queue/snapshot advice (not ₹0). */
    static double standingProbeOrderAmount(CustomerRiskMetrics m) {
        if (m == null) {
            return 0;
        }
        double avg = m.getAverageOrderValueLast90Days() > 0
                ? m.getAverageOrderValueLast90Days()
                : m.getAverageOrderValue();
        return avg > 0 ? avg : 0;
    }

    public CreditDecisionAuditDocument overrideDecision(
            String auditId,
            String newDecision,
            String reason,
            String overrideBy
    ) {
        return auditService.override(auditId, newDecision, reason, overrideBy);
    }

    public PaymentDateOverride setManualHold(String customerKey, boolean hold, String reason, String by) {
        PaymentDateOverride existing = paymentDateOverrideRepository.findFirstByCustomerKeyOrderByIdAsc(customerKey)
                .orElseThrow(() -> new IllegalArgumentException("Customer master not found"));
        PaymentDateOverride updated = PaymentDateOverrideCopy.withManualHold(existing, hold, reason, by);
        return paymentDateOverrideRepository.save(updated);
    }

    public Map<String, Object> applyRecommendedLimit(String customerOrKey, String by) {
        CustomerRiskMetrics m = metricsService.buildForCustomer(customerOrKey)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found"));
        CreditRiskEngineConfigDocument cfg = configService.getOrCreate();
        CustomerRiskScore score = scoringService.score(m, cfg);
        Double recommended = scoringService.recommendCreditLimit(m, score);
        if (recommended == null || recommended <= 0) {
            throw new IllegalArgumentException("No recommended credit limit available for this customer");
        }
        PaymentDateOverride existing = paymentDateOverrideRepository.findFirstByCustomerKeyOrderByIdAsc(m.getCustomerKey())
                .orElseGet(() -> PaymentDateOverrideCopy.newShell(m.getCustomerKey(), m.getCustomerName()));
        Double previous = existing.creditLimitOverride();
        PaymentDateOverride updated = PaymentDateOverrideCopy.withCreditLimitOverride(existing, recommended);
        paymentDateOverrideRepository.save(updated);
        CreditLimitAuditDocument audit = new CreditLimitAuditDocument();
        audit.setCustomerKey(m.getCustomerKey());
        audit.setCustomerName(m.getCustomerName());
        audit.setPreviousLimit(previous);
        audit.setNewLimit(recommended);
        audit.setSource("RECOMMENDED_APPLY");
        audit.setChangedBy(by);
        audit.setChangedAt(java.time.Instant.now());
        creditLimitAuditRepository.save(audit);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("customerKey", m.getCustomerKey());
        out.put("customerName", m.getCustomerName());
        out.put("appliedCreditLimit", recommended);
        out.put("appliedBy", by);
        return out;
    }

    public PaymentDateOverride setPaymentTermsDays(String customerKey, Integer days, String by) {
        if (days != null && days < 0) {
            throw new IllegalArgumentException("paymentTermsDays cannot be negative");
        }
        PaymentDateOverride existing = paymentDateOverrideRepository.findFirstByCustomerKeyOrderByIdAsc(customerKey)
                .orElseThrow(() -> new IllegalArgumentException("Customer master not found"));
        return paymentDateOverrideRepository.save(PaymentDateOverrideCopy.withPaymentTermsDays(existing, days));
    }

    public CreditPaymentEventUpload uploadPaymentEvents(org.springframework.web.multipart.MultipartFile file, String by)
            throws java.io.IOException {
        CreditPaymentEventUpload saved = paymentEventService.store(file, by);
        try {
            rebuildSnapshots();
        } catch (RuntimeException ex) {
            logger.warn("Snapshot rebuild after payment events upload failed: {}", ex.getMessage());
        }
        return saved;
    }

    public List<CreditPaymentPromiseDocument> listPromises(String customerKey) {
        return promiseService.forCustomer(customerKey);
    }

    public CreditPaymentPromiseDocument createPromise(
            String customerKey,
            String customerName,
            String voucherNo,
            String promiseDate,
            Integer daysUntil,
            Double promiseAmount,
            String note,
            String by
    ) {
        CreditPaymentPromiseDocument saved = promiseService.create(
                customerKey, customerName, voucherNo, promiseDate, daysUntil, promiseAmount, note, by
        );
        try {
            summary(saved.getCustomerKey());
        } catch (RuntimeException ex) {
            logger.warn("Snapshot refresh after cash promise failed: {}", ex.getMessage());
        }
        return saved;
    }

    public CreditPaymentPromiseDocument updatePromise(String id, boolean fulfilled, boolean broken, String by) {
        CreditPaymentPromiseDocument saved = promiseService.updateStatus(id, fulfilled, broken, by);
        try {
            summary(saved.getCustomerKey());
        } catch (RuntimeException ex) {
            logger.warn("Snapshot refresh after cash promise update failed: {}", ex.getMessage());
        }
        return saved;
    }

    public void deletePromise(String id) {
        String customerKey = promiseService.find(id).map(CreditPaymentPromiseDocument::getCustomerKey).orElse(null);
        promiseService.delete(id);
        if (customerKey != null) {
            try {
                summary(customerKey);
            } catch (RuntimeException ex) {
                logger.warn("Snapshot refresh after cash promise delete failed: {}", ex.getMessage());
            }
        }
    }

    public List<CreditRiskSnapshotDocument> listSnapshots() {
        return snapshotService.listAll();
    }

    public List<CreditRiskSnapshotDocument> listActionQueue(String bucket) {
        return snapshotService.listByBucket(bucket);
    }

    public CreditRiskEngineConfigDocument getConfig() {
        return configService.getOrCreate();
    }

    public CreditRiskEngineConfigDocument updateConfig(CreditRiskEngineConfigDocument doc, String by) {
        return configService.update(doc, by);
    }

    public List<CreditDecisionAuditDocument> audit(String customerKey, int limit) {
        if (customerKey != null && !customerKey.isBlank()) {
            return new ArrayList<>(auditService.forCustomer(customerKey));
        }
        return auditService.recent(limit);
    }

    public CreditRiskCommitteeSummary committeeSummary() {
        return insightsService.committeeSummary();
    }

    public List<CreditRiskCollectTodayItem> collectToday() {
        return insightsService.collectToday();
    }

    public List<CreditLimitAuditDocument> limitHistory(String customerKey) {
        if (customerKey == null || customerKey.isBlank()) {
            return List.of();
        }
        return creditLimitAuditRepository.findByCustomerKeyOrderByChangedAtDesc(customerKey);
    }
}
