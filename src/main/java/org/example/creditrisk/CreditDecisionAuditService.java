package org.example.creditrisk;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class CreditDecisionAuditService {

    private final CreditDecisionAuditRepository repository;

    public CreditDecisionAuditService(CreditDecisionAuditRepository repository) {
        this.repository = repository;
    }

    public CreditDecisionAuditDocument record(OrderDecisionResult result, String decidedBy, String referenceId) {
        CreditDecisionAuditDocument doc = new CreditDecisionAuditDocument();
        doc.setCustomerKey(result.customerKey());
        doc.setCustomerName(result.customerName());
        doc.setOrderAmount(result.orderAmount());
        doc.setReferenceId(referenceId);
        doc.setOrderDecision(result.orderDecision());
        doc.setPaymentDecision(result.paymentDecision());
        doc.setPaymentScore(result.paymentScore());
        doc.setBusinessScore(result.businessScore());
        doc.setRiskScore(result.riskScore());
        doc.setRiskCategory(result.riskCategory());
        doc.setCreditLimit(result.creditLimit());
        doc.setCurrentOutstanding(result.currentOutstanding());
        doc.setOverdueAmount(result.overdueAmount());
        doc.setProjectedExposure(result.projectedExposure());
        doc.setRequiredPayment(result.requiredPayment());
        doc.setReasons(result.reasons());
        doc.setDecidedBy(decidedBy);
        doc.setDecidedAt(Instant.now());
        return repository.save(doc);
    }

    public CreditDecisionAuditDocument override(
            String auditId,
            String newDecision,
            String reason,
            String overrideBy
    ) {
        CreditDecisionAuditDocument doc = repository.findById(auditId)
                .orElseThrow(() -> new IllegalArgumentException("Audit record not found"));
        doc.setOriginalOrderDecision(doc.getOrderDecision());
        doc.setOverrideOrderDecision(newDecision);
        doc.setOrderDecision(newDecision);
        doc.setOverrideReason(reason);
        doc.setOverrideBy(overrideBy);
        doc.setOverrideAt(Instant.now());
        return repository.save(doc);
    }

    public List<CreditDecisionAuditDocument> forCustomer(String customerKey) {
        return repository.findByCustomerKeyOrderByDecidedAtDesc(customerKey);
    }

    public List<CreditDecisionAuditDocument> recent(int limit) {
        List<CreditDecisionAuditDocument> all = repository.findAll();
        all.sort((a, b) -> {
            Instant ia = a.getDecidedAt() != null ? a.getDecidedAt() : Instant.EPOCH;
            Instant ib = b.getDecidedAt() != null ? b.getDecidedAt() : Instant.EPOCH;
            return ib.compareTo(ia);
        });
        return all.size() <= limit ? all : all.subList(0, limit);
    }
}
