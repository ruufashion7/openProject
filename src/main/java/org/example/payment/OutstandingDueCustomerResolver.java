package org.example.payment;

import org.example.customer.CustomerIdentity;
import org.example.upload.ExcelUploadHeaderRules;
import org.example.upload.ReceivableAgeingReportUpload;
import org.example.upload.ReceivableAgeingReportUploadRepository;
import org.example.upload.UploadedExcelFile;
import org.example.upload.UploadedExcelSheet;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Customers who should appear on the Drive payment sheet: outstanding balance &gt; 0,
 * plus retained customers (even at ₹0). Sorted by amount high → low.
 */
@Service
public class OutstandingDueCustomerResolver {

    private final ReceivableAgeingReportUploadRepository receivableUploadRepository;
    private final PaymentDateOverrideRepository paymentDateOverrideRepository;
    private final CustomerExclusionService customerExclusionService;

    public OutstandingDueCustomerResolver(
            ReceivableAgeingReportUploadRepository receivableUploadRepository,
            PaymentDateOverrideRepository paymentDateOverrideRepository,
            CustomerExclusionService customerExclusionService
    ) {
        this.receivableUploadRepository = receivableUploadRepository;
        this.paymentDateOverrideRepository = paymentDateOverrideRepository;
        this.customerExclusionService = customerExclusionService;
    }

    public List<DriveSheetCustomer> customersForDriveSheet() {
        List<PaymentDateOverride> allOverridesList = paymentDateOverrideRepository.findAll();
        Map<String, PaymentDateOverride> byKey = new LinkedHashMap<>();
        for (PaymentDateOverride override : allOverridesList) {
            if (override.customerKey() != null && !override.customerKey().isBlank()) {
                byKey.putIfAbsent(override.customerKey(), override);
            }
        }
        List<PaymentDateOverride> excludedOverrides = allOverridesList.stream()
                .filter(PaymentDateOverride::isExcluded)
                .toList();

        ReceivableAgeingReportUpload latest = receivableUploadRepository.findTopByOrderByUploadedAtDesc();
        if (latest == null || latest.file() == null) {
            return retainedOnly(allOverridesList);
        }

        Map<String, Double> amountByKey = new HashMap<>();
        Map<String, String> displayNameByKey = new LinkedHashMap<>();
        UploadedExcelFile file = latest.file();
        if (file.sheets() != null) {
            for (UploadedExcelSheet sheet : file.sheets()) {
                List<String> customerHeaders = sheet.headers().stream()
                        .filter(ExcelUploadHeaderRules::isCustomerHeader)
                        .toList();
                if (customerHeaders.isEmpty()) {
                    continue;
                }
                List<String> amountHeaders = sheet.headers().stream()
                        .filter(ExcelUploadHeaderRules::isAmountHeader)
                        .toList();
                if (amountHeaders.isEmpty()) {
                    continue;
                }
                for (Map<String, String> row : sheet.rows()) {
                    Optional<String> customerValue = firstCustomerValue(row, customerHeaders);
                    if (customerValue.isEmpty()) {
                        continue;
                    }
                    String displayName = customerValue.get();
                    String key = CustomerIdentity.normalizeKey(displayName);
                    if (key.isBlank()) {
                        continue;
                    }
                    double rowAmount = rowTotalAmount(row, amountHeaders);
                    if (rowAmount == 0.0) {
                        continue;
                    }
                    PaymentDateOverride matched = findFuzzyMatch(displayName, key, byKey);
                    if (customerExclusionService.isExcludedForUploadRow(
                            displayName, key, excludedOverrides, matched)) {
                        continue;
                    }
                    String resolvedKey = matched != null && matched.customerKey() != null
                            ? matched.customerKey()
                            : key;
                    amountByKey.merge(resolvedKey, rowAmount, Double::sum);
                    displayNameByKey.putIfAbsent(resolvedKey, displayName);
                }
            }
        }

        List<DriveSheetCustomer> results = new ArrayList<>();
        Set<String> added = new HashSet<>();
        for (Map.Entry<String, Double> entry : amountByKey.entrySet()) {
            PaymentDateOverride override = resolveOverride(
                    entry.getKey(), displayNameByKey.get(entry.getKey()), byKey);
            if (override != null && added.add(override.customerKey())) {
                results.add(new DriveSheetCustomer(override, entry.getValue()));
            }
        }

        for (PaymentDateOverride override : allOverridesList) {
            if (!override.isRetained() || override.isExcluded()) {
                continue;
            }
            String key = override.customerKey();
            if (key == null || key.isBlank() || added.contains(key)) {
                continue;
            }
            added.add(key);
            results.add(new DriveSheetCustomer(override, 0.0));
        }

        results.sort(driveSheetOrder());
        return List.copyOf(results);
    }

    private List<DriveSheetCustomer> retainedOnly(List<PaymentDateOverride> allOverridesList) {
        List<DriveSheetCustomer> results = allOverridesList.stream()
                .filter(PaymentDateOverride::isRetained)
                .filter(override -> !override.isExcluded())
                .filter(override -> override.customerKey() != null && !override.customerKey().isBlank())
                .map(override -> new DriveSheetCustomer(override, 0.0))
                .sorted(driveSheetOrder())
                .toList();
        return List.copyOf(results);
    }

    private static Comparator<DriveSheetCustomer> driveSheetOrder() {
        return Comparator
                .comparingDouble(DriveSheetCustomer::outstandingAmount).reversed()
                .thenComparing(
                        customer -> customer.customer().customerName() != null
                                ? customer.customer().customerName()
                                : customer.customerKey(),
                        String.CASE_INSENSITIVE_ORDER);
    }

    private PaymentDateOverride resolveOverride(
            String key,
            String displayName,
            Map<String, PaymentDateOverride> byKey
    ) {
        PaymentDateOverride exact = byKey.get(key);
        if (exact != null) {
            return exact;
        }
        if (displayName != null && !displayName.isBlank()) {
            PaymentDateOverride fuzzy = findFuzzyMatch(displayName, key, byKey);
            if (fuzzy != null) {
                return fuzzy;
            }
            return PaymentDateOverrideCopy.newShell(key, displayName);
        }
        return PaymentDateOverrideCopy.newShell(key, key);
    }

    private static PaymentDateOverride findFuzzyMatch(
            String displayName,
            String customerKey,
            Map<String, PaymentDateOverride> allOverrides
    ) {
        PaymentDateOverride exact = allOverrides.get(customerKey);
        if (exact != null) {
            return exact;
        }
        PaymentDateOverride best = null;
        double bestScore = 0;
        for (PaymentDateOverride candidate : allOverrides.values()) {
            double score = CustomerIdentity.similarity(displayName, candidate.customerName());
            if (score >= CustomerIdentity.FUZZY_MATCH_THRESHOLD && score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private static Optional<String> firstCustomerValue(Map<String, String> row, List<String> headers) {
        for (String header : headers) {
            String value = row.get(header);
            if (value != null && !value.isBlank()) {
                return Optional.of(value.trim());
            }
        }
        return Optional.empty();
    }

    private static double rowTotalAmount(Map<String, String> row, List<String> amountHeaders) {
        Double explicitTotal = null;
        double bucketSum = 0.0;
        for (String header : amountHeaders) {
            double amount = parseAmount(row.get(header));
            if (amount == 0.0) {
                continue;
            }
            String normalized = header.trim().toLowerCase();
            if (normalized.contains("total")
                    || normalized.contains("outstanding")
                    || normalized.equals("due")
                    || normalized.contains("balance")) {
                explicitTotal = explicitTotal == null ? amount : explicitTotal + amount;
            } else {
                bucketSum += amount;
            }
        }
        if (explicitTotal != null) {
            return explicitTotal;
        }
        return bucketSum;
    }

    private static double parseAmount(String value) {
        if (value == null || value.isBlank()) {
            return 0.0;
        }
        String cleaned = value.replace(",", "")
                .replaceAll("[^0-9.\\-]", "");
        if (cleaned.isBlank() || "-".equals(cleaned) || ".".equals(cleaned)) {
            return 0.0;
        }
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException ex) {
            return 0.0;
        }
    }
}
