package org.example.creditrisk;

import org.example.customer.CustomerIdentity;
import org.example.payment.PaymentDateOverride;
import org.example.payment.PaymentDateOverrideRepository;
import org.example.settings.CreditLimitResolution;
import org.example.settings.CustomerCreditLimitService;
import org.example.upload.DetailedSalesInvoicesUpload;
import org.example.upload.DetailedSalesInvoicesUploadRepository;
import org.example.upload.ExcelUploadHeaderRules;
import org.example.upload.ReceivableAgeingReportUpload;
import org.example.upload.ReceivableAgeingReportUploadRepository;
import org.example.upload.UploadedExcelFile;
import org.example.upload.UploadedExcelSheet;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class CustomerRiskMetricsService {

    private final PaymentDateOverrideRepository paymentDateOverrideRepository;
    private final ReceivableAgeingReportUploadRepository receivableAgeingReportUploadRepository;
    private final DetailedSalesInvoicesUploadRepository detailedSalesInvoicesUploadRepository;
    private final CustomerCreditLimitService customerCreditLimitService;
    private final CreditRiskEngineConfigService configService;
    private final CreditPaymentEventService paymentEventService;
    private final CreditPaymentPromiseService promiseService;

    public CustomerRiskMetricsService(
            PaymentDateOverrideRepository paymentDateOverrideRepository,
            ReceivableAgeingReportUploadRepository receivableAgeingReportUploadRepository,
            DetailedSalesInvoicesUploadRepository detailedSalesInvoicesUploadRepository,
            CustomerCreditLimitService customerCreditLimitService,
            CreditRiskEngineConfigService configService,
            CreditPaymentEventService paymentEventService,
            CreditPaymentPromiseService promiseService
    ) {
        this.paymentDateOverrideRepository = paymentDateOverrideRepository;
        this.receivableAgeingReportUploadRepository = receivableAgeingReportUploadRepository;
        this.detailedSalesInvoicesUploadRepository = detailedSalesInvoicesUploadRepository;
        this.customerCreditLimitService = customerCreditLimitService;
        this.configService = configService;
        this.paymentEventService = paymentEventService;
        this.promiseService = promiseService;
    }

    public Optional<CustomerRiskMetrics> buildForCustomer(String customerOrPhone) {
        if (customerOrPhone == null || customerOrPhone.isBlank()) {
            return Optional.empty();
        }
        String trimmed = customerOrPhone.trim();
        PaymentDateOverride override = null;
        String displayName = trimmed;
        String key = CustomerIdentity.normalizeKey(trimmed);

        if (trimmed.matches("\\d{10,}")) {
            for (PaymentDateOverride o : paymentDateOverrideRepository.findAll()) {
                if (o.phoneNumber() != null && o.phoneNumber().replaceAll("\\D", "").contains(trimmed.replaceAll("\\D", ""))) {
                    override = o;
                    key = o.customerKey();
                    displayName = o.customerName();
                    break;
                }
            }
        } else {
            override = paymentDateOverrideRepository.findFirstByCustomerKeyOrderByIdAsc(key).orElse(null);
            if (override == null) {
                for (PaymentDateOverride o : paymentDateOverrideRepository.findAll()) {
                    if (CustomerIdentity.matchesFuzzy(trimmed, o.customerName())
                            || CustomerIdentity.matchesFuzzy(trimmed, o.customerKey())) {
                        override = o;
                        key = o.customerKey();
                        displayName = o.customerName();
                        break;
                    }
                }
            } else {
                displayName = override.customerName() != null ? override.customerName() : displayName;
            }
        }

        CustomerRiskMetrics metrics = new CustomerRiskMetrics();
        metrics.setCustomerKey(key);
        metrics.setCustomerName(displayName);
        if (override != null) {
            metrics.setCustomerCategory(override.customerCategory());
            metrics.setPhoneNumber(override.phoneNumber());
            metrics.setManualHold(override.isManualHold());
            metrics.setManualHoldReason(override.manualHoldReason());
            metrics.setCreditLimitOverride(override.creditLimitOverride());
            metrics.setPaymentTermsDays(override.paymentTermsDays());
            metrics.setNextPaymentDate(override.nextPaymentDate());
        }

        CreditRiskEngineConfigDocument cfg = configService.getOrCreate();
        fillAgeing(metrics, key, displayName);
        fillLedger(metrics, key, displayName, cfg);

        if (paymentEventService.hasUpload()) {
            metrics.setPaymentFailuresLast90Days(
                    paymentEventService.countFailures(key, displayName, cfg.getPaymentFailureWindowDays())
            );
            metrics.getDataQualityWarnings().remove("PAYMENT_STATUS_MISSING");
        }
        // Overall Outstanding Due date + per-invoice/cash collect promises.
        promiseService.refreshBrokenFlags(key);
        int broken = CreditPaymentPromiseService.brokenFromNextPaymentDate(
                metrics.getNextPaymentDate(),
                metrics.getCurrentOutstanding()
        ) + promiseService.countBroken(key);
        metrics.setBrokenPaymentPromises(broken);
        metrics.setOpenPaymentPromiseCount(promiseService.countOpen(key));
        metrics.setOpenPaymentPromiseAmount(promiseService.sumOpenAmount(key));

        CreditLimitResolution resolution = customerCreditLimitService.resolve(metrics.getCurrentOutstanding(), override);
        metrics.setEffectiveCreditLimit(resolution.effectiveCreditLimit());
        metrics.setCreditLimitSource(resolution.creditLimitSource());
        Double util = resolution.creditLimitUtilization();
        metrics.setCreditUtilization(util == null ? null : util * 100.0);

        if (metrics.getDataQualityWarnings().isEmpty() || true) {
            addDefaultWarnings(metrics);
        }
        buildAlerts(metrics);
        return Optional.of(metrics);
    }

    private void addDefaultWarnings(CustomerRiskMetrics metrics) {
        List<String> w = new ArrayList<>(metrics.getDataQualityWarnings());
        if (!w.contains("DUE_DATE_MISSING")) {
            // filled in ledger when no due-date columns seen globally — set if proxy used
        }
        metrics.setDataQualityWarnings(w);
    }

    private void fillAgeing(CustomerRiskMetrics metrics, String key, String displayName) {
        ReceivableAgeingReportUpload latest = receivableAgeingReportUploadRepository.findTopByOrderByUploadedAtDesc();
        if (latest == null || latest.file() == null) {
            metrics.getDataQualityWarnings().add("RECEIVABLE_AGEING_MISSING");
            return;
        }
        double within = 0;
        double mid = 0;
        double beyond = 0;
        double total = 0;
        Double totalCol = null;
        UploadedExcelFile file = latest.file();
        for (UploadedExcelSheet sheet : file.sheets()) {
            List<String> customerHeaders = sheet.headers().stream().filter(ExcelUploadHeaderRules::isCustomerHeader).toList();
            List<String> amountHeaders = sheet.headers().stream().filter(ExcelUploadHeaderRules::isAmountHeader).toList();
            if (customerHeaders.isEmpty() || amountHeaders.isEmpty()) {
                continue;
            }
            for (Map<String, String> row : sheet.rows()) {
                String customer = CreditRiskParseSupport.firstNonBlank(row, customerHeaders);
                if (customer == null || !rowMatchesCustomer(customer, key, displayName)) {
                    continue;
                }
                for (String amountHeader : amountHeaders) {
                    double amount = CreditRiskParseSupport.parseAmount(row.get(amountHeader));
                    if (amount == 0) {
                        continue;
                    }
                    CreditRiskParseSupport.AmountBucket bucket = CreditRiskParseSupport.classifyAmountHeader(amountHeader);
                    switch (bucket) {
                        case TOTAL -> totalCol = totalCol == null ? amount : totalCol + amount;
                        case WITHIN -> within += amount;
                        case MID -> mid += amount;
                        case BEYOND -> beyond += amount;
                        default -> total += amount;
                    }
                }
            }
        }
        if (totalCol != null) {
            total = totalCol;
        } else if (within > 0 || mid > 0 || beyond > 0) {
            total = within + mid + beyond;
        }
        metrics.setWithinAmount(within);
        metrics.setMidAmount(mid);
        metrics.setBeyondAmount(beyond);
        metrics.setCurrentOutstanding(total);
        // Beyond bucket treated as overdue when ledger has no open lines
        if (metrics.getOverdueAmount() <= 0 && beyond > 0) {
            metrics.setOverdueAmount(beyond);
        }
    }

    private void fillLedger(
            CustomerRiskMetrics metrics,
            String key,
            String displayName,
            CreditRiskEngineConfigDocument cfg
    ) {
        DetailedSalesInvoicesUpload latest = detailedSalesInvoicesUploadRepository.findTopByOrderByUploadedAtDesc();
        if (latest == null || latest.file() == null) {
            metrics.getDataQualityWarnings().add("DETAILED_SALES_MISSING");
            return;
        }
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        int categoryTermsDays = cfg.defaultPaymentTermsDaysForCategory(metrics.getCustomerCategory());
        Integer customerTerms = metrics.getPaymentTermsDays();
        boolean usedCategoryTermsFallback = false;
        boolean usedCustomerTermsFallback = false;
        boolean usedInvoiceAgeProxy = false;
        boolean sawDueDate = false;
        boolean sawMargin = false;
        boolean sawPaymentStatus = false;
        int totalInvoices = 0;
        int paid = 0;
        int partial = 0;
        int unpaid = 0;
        int overdueCount = 0;
        int maxOverdueDays = 0;
        double overdueAmt = 0;
        int successfulPayments = 0;
        int lateCount = 0;
        double delayAmountSum = 0;
        double delayAmountWeight = 0;
        double maxDelay = 0;
        int paymentFailures = 0;
        double lifetime = 0;
        double s7 = 0, s30 = 0, s90 = 0, s180 = 0, s365 = 0;
        double prev30 = 0, prev90 = 0;
        int o7 = 0, o30 = 0, o90 = 0, o180 = 0;
        int totalOrders = 0;
        double largest = 0;
        LocalDate first = null;
        LocalDate last = null;
        double lastOrderAmt = 0;
        LocalDate lastPayDate = null;
        double lastPayAmt = 0;
        List<Double> orderAmounts = new ArrayList<>();
        double marginSum = 0;
        int marginN = 0;
        int cancelled = 0;

        for (UploadedExcelSheet sheet : latest.file().sheets()) {
            List<String> customerHeaders = sheet.headers().stream().filter(ExcelUploadHeaderRules::isCustomerHeader).toList();
            List<String> invoiceHeaders = sheet.headers().stream().filter(ExcelUploadHeaderRules::isInvoiceDateHeader).toList();
            List<String> receivedHeaders = sheet.headers().stream().filter(ExcelUploadHeaderRules::isReceivedHeader).toList();
            List<String> dueHeaders = sheet.headers().stream().filter(ExcelUploadHeaderRules::isCurrentDueHeader).toList();
            List<String> dueDateHeaders = sheet.headers().stream().filter(ExcelUploadHeaderRules::isDueDateHeader).toList();
            List<String> termsHeaders = sheet.headers().stream().filter(ExcelUploadHeaderRules::isPaymentTermsHeader).toList();
            List<String> statusHeaders = sheet.headers().stream().filter(ExcelUploadHeaderRules::isInvoiceStatusHeader).toList();
            List<String> payStatusHeaders = sheet.headers().stream().filter(ExcelUploadHeaderRules::isPaymentStatusHeader).toList();
            List<String> marginHeaders = sheet.headers().stream().filter(ExcelUploadHeaderRules::isMarginHeader).toList();
            List<String> cnHeaders = sheet.headers().stream().filter(ExcelUploadHeaderRules::isCreditNoteHeader).toList();
            if (customerHeaders.isEmpty()) {
                continue;
            }
            if (!dueDateHeaders.isEmpty() || !termsHeaders.isEmpty()) {
                sawDueDate = true;
            }
            if (!marginHeaders.isEmpty()) {
                sawMargin = true;
            }
            if (!payStatusHeaders.isEmpty()) {
                sawPaymentStatus = true;
            }

            for (Map<String, String> row : sheet.rows()) {
                String customer = CreditRiskParseSupport.firstNonBlank(row, customerHeaders);
                if (customer == null || !rowMatchesCustomer(customer, key, displayName)) {
                    continue;
                }
                if (CreditRiskParseSupport.isVoidOrCancelled(row, statusHeaders)) {
                    cancelled++;
                    continue;
                }
                double creditNote = CreditRiskParseSupport.sumAmounts(row, cnHeaders);
                double received = CreditRiskParseSupport.sumAmounts(row, receivedHeaders);
                double currentDue = CreditRiskParseSupport.sumAmounts(row, dueHeaders);
                double lineTotal = Math.max(0, received + currentDue - creditNote);
                if (lineTotal <= 0.01 && currentDue <= 0.01 && received <= 0.01) {
                    continue;
                }
                String invoiceDateStr = CreditRiskParseSupport.firstNonBlank(row, invoiceHeaders);
                LocalDate invoiceDate = CreditRiskParseSupport.parseInvoiceDate(invoiceDateStr);
                // Hybrid due: Due Date → row terms → customer terms → category default → invoice-age proxy
                LocalDate dueDate = null;
                String dueDateStr = CreditRiskParseSupport.firstNonBlank(row, dueDateHeaders);
                if (dueDateStr != null) {
                    dueDate = CreditRiskParseSupport.parseInvoiceDate(dueDateStr);
                }
                if (dueDate == null && invoiceDate != null && !termsHeaders.isEmpty()) {
                    int terms = (int) CreditRiskParseSupport.parseAmount(CreditRiskParseSupport.firstNonBlank(row, termsHeaders));
                    if (terms > 0) {
                        dueDate = invoiceDate.plusDays(terms);
                    }
                }
                if (dueDate == null && invoiceDate != null && customerTerms != null && customerTerms > 0) {
                    dueDate = invoiceDate.plusDays(customerTerms);
                    usedCustomerTermsFallback = true;
                }
                if (dueDate == null && invoiceDate != null && categoryTermsDays > 0) {
                    dueDate = invoiceDate.plusDays(categoryTermsDays);
                    usedCategoryTermsFallback = true;
                }

                totalInvoices++;
                lifetime += lineTotal;
                totalOrders++;
                orderAmounts.add(lineTotal);
                if (lineTotal > largest) {
                    largest = lineTotal;
                }
                if (invoiceDate != null) {
                    if (first == null || invoiceDate.isBefore(first)) {
                        first = invoiceDate;
                    }
                    if (last == null || invoiceDate.isAfter(last)) {
                        last = invoiceDate;
                        lastOrderAmt = lineTotal;
                    }
                    long age = ChronoUnit.DAYS.between(invoiceDate, today);
                    if (age <= 7) {
                        s7 += lineTotal;
                        o7++;
                    }
                    if (age <= 30) {
                        s30 += lineTotal;
                        o30++;
                    } else if (age <= 60) {
                        prev30 += lineTotal;
                    }
                    if (age <= 90) {
                        s90 += lineTotal;
                        o90++;
                    } else if (age <= 180) {
                        prev90 += lineTotal;
                    }
                    if (age <= 180) {
                        s180 += lineTotal;
                        o180++;
                    }
                    if (age <= 365) {
                        s365 += lineTotal;
                    }
                }

                if (currentDue <= 0.01) {
                    paid++;
                } else if (received > 0.01) {
                    partial++;
                } else {
                    unpaid++;
                }
                if (received > 0.01) {
                    successfulPayments++;
                    if (lastPayDate == null || (invoiceDate != null && invoiceDate.isAfter(lastPayDate))) {
                        lastPayDate = invoiceDate != null ? invoiceDate : lastPayDate;
                        lastPayAmt = received;
                    }
                }

                // Overdue: prefer due date; else proxy invoice age for open dues
                if (currentDue > 0.01) {
                    Integer overdueDays = null;
                    if (dueDate != null) {
                        long d = ChronoUnit.DAYS.between(dueDate, today);
                        if (d > 0) {
                            overdueDays = (int) d;
                        }
                    } else if (invoiceDate != null) {
                        overdueDays = (int) Math.max(0, ChronoUnit.DAYS.between(invoiceDate, today));
                        usedInvoiceAgeProxy = true;
                    }
                    if (overdueDays != null && overdueDays > 0) {
                        overdueCount++;
                        overdueAmt += currentDue;
                        lateCount++;
                        if (currentDue >= cfg.getMinOpenDueForAgeingMetrics()) {
                            maxOverdueDays = Math.max(maxOverdueDays, overdueDays);
                            delayAmountSum += overdueDays * currentDue;
                            delayAmountWeight += currentDue;
                            maxDelay = Math.max(maxDelay, overdueDays);
                        }
                    }
                } else if (dueDate != null && invoiceDate != null && received > 0.01) {
                    // paid: proxy delay as 0 when paid and no payment date (on-time assumed if due date in future at upload)
                    long delay = ChronoUnit.DAYS.between(dueDate, today);
                    // Without payment date we cannot know; treat fully paid as on-time
                }

                for (String h : payStatusHeaders) {
                    String st = row.get(h);
                    if (st != null) {
                        String n = st.toLowerCase(Locale.ROOT);
                        if (n.contains("fail") || n.contains("bounce") || n.contains("return")) {
                            paymentFailures++;
                        }
                    }
                }
                for (String h : marginHeaders) {
                    double m = CreditRiskParseSupport.parseAmount(row.get(h));
                    if (m != 0) {
                        marginSum += m;
                        marginN++;
                    }
                }
            }
        }

        metrics.setTotalInvoices(totalInvoices);
        metrics.setPaidInvoices(paid);
        metrics.setPartiallyPaidInvoices(partial);
        metrics.setUnpaidInvoices(unpaid);
        metrics.setSuccessfulPaymentCount(successfulPayments);
        metrics.setOverdueInvoiceCount(overdueCount);
        metrics.setMaximumOverdueDays(maxOverdueDays);
        double overdueTotal = Math.max(metrics.getOverdueAmount(), overdueAmt);
        if (overdueTotal > 0) {
            metrics.setOverdueAmount(overdueTotal);
        }
        metrics.setLatePaymentCount(lateCount);
        metrics.setAveragePaymentDelayDays(
                delayAmountWeight > 0 ? delayAmountSum / delayAmountWeight : 0);
        metrics.setMaximumPaymentDelayDays(maxDelay);
        metrics.setOnTimePaymentPercentage(computeOnTimePaymentPercentage(
                metrics.getCurrentOutstanding(),
                overdueTotal,
                paid,
                overdueCount
        ));
        metrics.setPaymentFailuresLast90Days(paymentFailures);
        metrics.setLifetimeSales(lifetime);
        metrics.setSalesLast7Days(s7);
        metrics.setSalesLast30Days(s30);
        metrics.setSalesLast90Days(s90);
        metrics.setSalesLast180Days(s180);
        metrics.setSalesLast365Days(s365);
        metrics.setSalesPrevious30Days(prev30);
        metrics.setSalesPrevious90Days(prev90);
        metrics.setTotalOrders(totalOrders);
        metrics.setOrdersLast7Days(o7);
        metrics.setOrdersLast30Days(o30);
        metrics.setOrdersLast90Days(o90);
        metrics.setOrdersLast180Days(o180);
        metrics.setLargestOrder(largest);
        metrics.setAverageOrderValue(totalOrders > 0 ? lifetime / totalOrders : 0);
        metrics.setAverageOrderValueLast90Days(o90 > 0 ? s90 / o90 : 0);
        metrics.setLastOrderAmount(lastOrderAmt);
        if (first != null) {
            metrics.setFirstOrderDate(first.toString());
            metrics.setCustomerTenureDays((int) ChronoUnit.DAYS.between(first, today));
        }
        if (last != null) {
            metrics.setLastOrderDate(last.toString());
        }
        if (lastPayDate != null) {
            metrics.setLastPaymentDate(lastPayDate.toString());
            metrics.setLastPaymentAmount(lastPayAmt);
        }
        metrics.setReturnCancellationCount(cancelled);
        if (marginN > 0) {
            metrics.setCustomerMarginPercent(marginSum / marginN);
        }
        if (metrics.getCustomerTenureDays() > 0) {
            double months = Math.max(1.0, metrics.getCustomerTenureDays() / 30.0);
            metrics.setOrderFrequencyPerMonth(totalOrders / months);
        }
        if (!sawDueDate && usedCustomerTermsFallback) {
            metrics.getDataQualityWarnings().add("DUE_DATE_USING_CUSTOMER_TERMS");
        } else if (!sawDueDate && usedCategoryTermsFallback) {
            metrics.getDataQualityWarnings().add("DUE_DATE_USING_CATEGORY_DEFAULT_TERMS");
        } else if (!sawDueDate && usedInvoiceAgeProxy) {
            metrics.getDataQualityWarnings().add("DUE_DATE_OR_PAYMENT_TERMS_MISSING");
        } else if (!sawDueDate) {
            metrics.getDataQualityWarnings().add("DUE_DATE_OR_PAYMENT_TERMS_MISSING");
        }
        if (!sawPaymentStatus && !paymentEventService.hasUpload()) {
            metrics.getDataQualityWarnings().add("PAYMENT_STATUS_MISSING");
        }
        if (!sawMargin) {
            metrics.getDataQualityWarnings().add("MARGIN_DATA_MISSING");
        }
    }

    /**
     * Share of exposure still within terms (by ₹), not historical paid invoice count.
     * ponytail: old formula (paid−openOverdueLines)/paid inflated scores for long-tenure accounts.
     */
    static double computeOnTimePaymentPercentage(
            double currentOutstanding,
            double overdueAmount,
            int paidInvoiceLines,
            int openOverdueInvoiceLines
    ) {
        if (currentOutstanding > 0.01) {
            double within = Math.max(0, currentOutstanding - Math.max(0, overdueAmount));
            return 100.0 * within / currentOutstanding;
        }
        int denom = paidInvoiceLines + openOverdueInvoiceLines;
        if (denom > 0) {
            return 100.0 * paidInvoiceLines / denom;
        }
        return paidInvoiceLines > 0 ? 100.0 : 0;
    }

    private static boolean rowMatchesCustomer(String customer, String key, String displayName) {
        String rowKey = CustomerIdentity.normalizeKey(customer);
        if (rowKey.equals(key)) {
            return true;
        }
        return CustomerIdentity.matchesFuzzy(customer, displayName)
                || CustomerIdentity.matchesFuzzy(customer, key);
    }

    private void buildAlerts(CustomerRiskMetrics m) {
        List<String> alerts = new ArrayList<>();
        if (m.getSalesPrevious30Days() > 0 && m.getSalesLast30Days() < m.getSalesPrevious30Days() * 0.7) {
            alerts.add("ORDER_VALUE_DECLINING");
        }
        if (m.getCreditUtilization() != null && m.getCreditUtilization() >= 90) {
            alerts.add("CREDIT_UTILIZATION_HIGH");
        }
        if (m.getOverdueAmount() > 0 && m.getMaximumOverdueDays() > 60) {
            alerts.add("OVERDUE_AMOUNT_INCREASING");
        }
        if (m.getAveragePaymentDelayDays() > 15) {
            alerts.add("PAYMENT_DELAY_INCREASING");
        }
        if (m.getPaymentFailuresLast90Days() > 0) {
            alerts.add("PAYMENT_FAILURE_INCREASED");
        }
        if (m.getBrokenPaymentPromises() > 0) {
            alerts.add("PAYMENT_PROMISE_BROKEN");
        }
        if (m.getOpenPaymentPromiseCount() > 0 && m.getOpenPaymentPromiseAmount() > 0) {
            alerts.add("COLLECT_CASH_DUE");
        }
        m.setAlerts(alerts);
    }
}
