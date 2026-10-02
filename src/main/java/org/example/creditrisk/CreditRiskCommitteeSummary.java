package org.example.creditrisk;

import java.util.List;
import java.util.Map;

public record CreditRiskCommitteeSummary(
        Map<String, Long> countsByBucket,
        double totalOverdueInQueue,
        long highUtilizationCount,
        long windDownCount,
        long manualHoldCount,
        List<CreditRiskCollectTodayItem> topOverdue
) {
}
