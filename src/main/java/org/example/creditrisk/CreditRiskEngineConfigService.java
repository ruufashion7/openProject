package org.example.creditrisk;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

@Service
public class CreditRiskEngineConfigService {

    private final CreditRiskEngineConfigRepository repository;

    public CreditRiskEngineConfigService(CreditRiskEngineConfigRepository repository) {
        this.repository = repository;
    }

    public CreditRiskEngineConfigDocument getOrCreate() {
        return repository.findById(CreditRiskEngineConfigDocument.DOCUMENT_ID)
                .orElseGet(() -> {
                    CreditRiskEngineConfigDocument doc = new CreditRiskEngineConfigDocument();
                    doc.setUpdatedAt(Instant.now());
                    return repository.save(doc);
                });
    }

    public CreditRiskEngineConfigDocument update(CreditRiskEngineConfigDocument incoming, String updatedBy) {
        CreditRiskEngineConfigDocument doc = getOrCreate();
        if (incoming.getOverdueHardBlockDays() > 0) {
            doc.setOverdueHardBlockDays(incoming.getOverdueHardBlockDays());
        }
        if (incoming.getNoPaymentAgainstDueDays() > 0) {
            doc.setNoPaymentAgainstDueDays(incoming.getNoPaymentAgainstDueDays());
        }
        if (incoming.getNewCustomerMaxTenureDays() > 0) {
            doc.setNewCustomerMaxTenureDays(incoming.getNewCustomerMaxTenureDays());
        }
        if (incoming.getNewCustomerInvoiceThreshold() > 0) {
            doc.setNewCustomerInvoiceThreshold(incoming.getNewCustomerInvoiceThreshold());
        }
        if (incoming.getCreditLimitBreachMode() != null && !incoming.getCreditLimitBreachMode().isBlank()) {
            doc.setCreditLimitBreachMode(incoming.getCreditLimitBreachMode());
        }
        if (incoming.getOverdueHardBlockDaysByCategory() != null) {
            doc.setOverdueHardBlockDaysByCategory(incoming.getOverdueHardBlockDaysByCategory());
        }
        if (incoming.getDefaultPaymentTermsDaysByCategory() != null) {
            doc.setDefaultPaymentTermsDaysByCategory(incoming.getDefaultPaymentTermsDaysByCategory());
        }
        doc.setMultipleOverdueInvoiceThreshold(incoming.getMultipleOverdueInvoiceThreshold());
        doc.setHighOverduePercentThreshold(incoming.getHighOverduePercentThreshold());
        doc.setLowOnTimePaymentPercent(incoming.getLowOnTimePaymentPercent());
        doc.setRepeatedLatePaymentCount(incoming.getRepeatedLatePaymentCount());
        doc.setPaymentFailureWindowDays(incoming.getPaymentFailureWindowDays());
        doc.setPaymentFailureCountThreshold(incoming.getPaymentFailureCountThreshold());
        doc.setCreditUtilizationWatchPercent(incoming.getCreditUtilizationWatchPercent());
        doc.setCreditUtilizationHighPercent(incoming.getCreditUtilizationHighPercent());
        doc.setUnusualOrderMultiplier(incoming.getUnusualOrderMultiplier());
        doc.setEnablePaymentPromiseRule(incoming.isEnablePaymentPromiseRule());
        doc.setEnablePaymentFailureRule(incoming.isEnablePaymentFailureRule());
        doc.setEnableNoPaymentAgainstDueRule(incoming.isEnableNoPaymentAgainstDueRule());
        doc.setRedistributeMarginWeight(incoming.isRedistributeMarginWeight());
        doc.setEnableLowMarginWithOverdueRule(incoming.isEnableLowMarginWithOverdueRule());
        doc.setLowMarginPercentThreshold(incoming.getLowMarginPercentThreshold());
        doc.setRiskCategoryVeryReliableMin(incoming.getRiskCategoryVeryReliableMin());
        doc.setRiskCategoryReliableMin(incoming.getRiskCategoryReliableMin());
        doc.setRiskCategoryWatchMin(incoming.getRiskCategoryWatchMin());
        doc.setRiskCategoryRiskyMin(incoming.getRiskCategoryRiskyMin());
        if (incoming.getScoringBands() != null) {
            doc.setScoringBands(incoming.getScoringBands());
        }
        doc.setUpdatedAt(Instant.now());
        doc.setUpdatedBy(updatedBy);
        return repository.save(doc);
    }

    public Optional<CreditRiskEngineConfigDocument> find() {
        return repository.findById(CreditRiskEngineConfigDocument.DOCUMENT_ID);
    }
}
