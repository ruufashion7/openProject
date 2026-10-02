package org.example.creditrisk;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class CreditRiskInsightsService {

    private final CreditRiskSnapshotRepository snapshotRepository;

    public CreditRiskInsightsService(CreditRiskSnapshotRepository snapshotRepository) {
        this.snapshotRepository = snapshotRepository;
    }

    public CreditRiskCommitteeSummary committeeSummary() {
        List<CreditRiskSnapshotDocument> all = snapshotRepository.findAll();
        Map<String, Long> counts = new HashMap<>();
        double totalOverdue = 0;
        long highUtil = 0;
        long windDown = 0;
        long holds = 0;

        for (CreditRiskSnapshotDocument s : all) {
            String bucket = s.getActionBucket() != null ? s.getActionBucket() : CreditRiskSnapshotService.BUCKET_CLEAR;
            if (!CreditRiskSnapshotService.BUCKET_CLEAR.equals(bucket)) {
                counts.merge(bucket, 1L, Long::sum);
            }
            if (s.isManualHold()) {
                holds++;
            }
            if (CreditRiskSnapshotService.BUCKET_WIND_DOWN.equals(bucket)) {
                windDown++;
            }
            if (s.getCreditUtilization() != null && s.getCreditUtilization() >= 90.0) {
                highUtil++;
            }
            if (isCollectBucket(bucket)) {
                totalOverdue += s.getOverdueAmount();
            }
        }

        List<CreditRiskCollectTodayItem> top = all.stream()
                .filter(s -> isCollectBucket(s.getActionBucket()))
                .sorted(Comparator.comparingDouble(CreditRiskSnapshotDocument::getOverdueAmount).reversed())
                .limit(15)
                .map(this::toCollectItem)
                .collect(Collectors.toList());

        return new CreditRiskCommitteeSummary(counts, totalOverdue, highUtil, windDown, holds, top);
    }

    public List<CreditRiskCollectTodayItem> collectToday() {
        return snapshotRepository.findAll().stream()
                .filter(s -> isCollectBucket(s.getActionBucket()) || hasCollectAlert(s))
                .sorted(Comparator.comparingDouble(CreditRiskSnapshotDocument::getOverdueAmount).reversed())
                .map(this::toCollectItem)
                .collect(Collectors.toList());
    }

    private static boolean isCollectBucket(String bucket) {
        if (bucket == null) {
            return false;
        }
        return CreditRiskSnapshotService.BUCKET_NEED_PAYMENT.equals(bucket)
                || CreditRiskSnapshotService.BUCKET_BLOCKED.equals(bucket);
    }

    private static boolean hasCollectAlert(CreditRiskSnapshotDocument s) {
        List<String> alerts = s.getAlerts();
        if (alerts == null) {
            return false;
        }
        return alerts.contains("COLLECT_CASH_DUE") || alerts.contains("PAYMENT_PROMISE_BROKEN");
    }

    private CreditRiskCollectTodayItem toCollectItem(CreditRiskSnapshotDocument s) {
        return new CreditRiskCollectTodayItem(
                s.getCustomerKey(),
                s.getCustomerName(),
                s.getPhoneNumber(),
                s.getOverdueAmount(),
                s.getActionBucket(),
                null,
                (int) Math.round(s.getRiskScore()),
                s.getRiskCategory()
        );
    }
}
