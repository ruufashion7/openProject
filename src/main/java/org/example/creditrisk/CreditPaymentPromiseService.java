package org.example.creditrisk;

import org.example.customer.CustomerIdentity;
import org.example.payment.PaymentDateRules;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class CreditPaymentPromiseService {

    private static final DateTimeFormatter PROMISE_DATE =
            DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);

    private final CreditPaymentPromiseRepository repository;
    private final CustomerSalesInvoiceLookupService invoiceLookup;

    public CreditPaymentPromiseService(
            CreditPaymentPromiseRepository repository,
            CustomerSalesInvoiceLookupService invoiceLookup
    ) {
        this.repository = repository;
        this.invoiceLookup = invoiceLookup;
    }

    public List<CreditPaymentPromiseDocument> forCustomer(String customerKey) {
        return repository.findByCustomerKeyOrderByPromiseDateDesc(CustomerIdentity.normalizeKey(customerKey));
    }

    public int countBroken(String customerKey) {
        return (int) repository.countByCustomerKeyAndBrokenTrue(CustomerIdentity.normalizeKey(customerKey));
    }

    public int countOpen(String customerKey) {
        return (int) forCustomer(customerKey).stream().filter(d -> !d.isFulfilled()).count();
    }

    public double sumOpenAmount(String customerKey) {
        return forCustomer(customerKey).stream()
                .filter(d -> !d.isFulfilled())
                .mapToDouble(CreditPaymentPromiseDocument::getPromiseAmount)
                .sum();
    }

    /** Mark past open promises as broken (idempotent). */
    public void refreshBrokenFlags(String customerKey) {
        for (CreditPaymentPromiseDocument doc : forCustomer(customerKey)) {
            if (doc.isFulfilled() || doc.isBroken()) {
                continue;
            }
            if (isPastAndOpen(doc.getPromiseDate())) {
                doc.setBroken(true);
                doc.setUpdatedAt(Instant.now());
                repository.save(doc);
            }
        }
    }

    /**
     * Outstanding Due {@code nextPaymentDate} is the overall payment promise.
     * Broken when that date is past and outstanding remains.
     */
    public static int brokenFromNextPaymentDate(String nextPaymentDate, double outstanding) {
        if (outstanding <= 0 || nextPaymentDate == null || nextPaymentDate.isBlank()) {
            return 0;
        }
        return isPastAndOpen(nextPaymentDate) ? 1 : 0;
    }

    public CreditPaymentPromiseDocument create(
            String customerKey,
            String customerName,
            String voucherNo,
            String promiseDate,
            Integer daysUntil,
            Double promiseAmount,
            String note,
            String by
    ) {
        CreditPaymentPromiseValidation.Resolved resolved = CreditPaymentPromiseValidation.resolve(
                invoiceLookup,
                repository,
                customerKey,
                customerName,
                voucherNo,
                promiseAmount,
                daysUntil,
                promiseDate,
                note
        );
        String date = resolvePromiseDate(promiseDate, daysUntil);
        if (date == null || date.isBlank()) {
            throw new IllegalArgumentException("promiseDate or daysUntil is required");
        }
        CreditPaymentPromiseDocument doc = new CreditPaymentPromiseDocument();
        doc.setCustomerKey(CustomerIdentity.normalizeKey(customerKey));
        doc.setCustomerName(customerName);
        doc.setVoucherNo(resolved.voucherNo());
        doc.setPromiseDate(date);
        doc.setPromiseAmount(resolved.promiseAmount());
        doc.setNote(resolved.note());
        doc.setFulfilled(false);
        doc.setBroken(isPastAndOpen(date));
        doc.setCreatedAt(Instant.now());
        doc.setUpdatedAt(Instant.now());
        doc.setUpdatedBy(by);
        return repository.save(doc);
    }

    public CreditPaymentPromiseDocument updateStatus(String id, boolean fulfilled, boolean broken, String by) {
        CreditPaymentPromiseDocument doc = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Promise not found"));
        doc.setFulfilled(fulfilled);
        doc.setBroken(broken && !fulfilled);
        doc.setUpdatedAt(Instant.now());
        doc.setUpdatedBy(by);
        return repository.save(doc);
    }

    public Optional<CreditPaymentPromiseDocument> find(String id) {
        return repository.findById(id);
    }

    public void delete(String id) {
        repository.deleteById(id);
    }

    static String resolvePromiseDate(String promiseDate, Integer daysUntil) {
        if (promiseDate != null && !promiseDate.isBlank()) {
            return promiseDate.trim();
        }
        if (daysUntil != null && daysUntil >= 0) {
            return LocalDate.now().plusDays(daysUntil).format(PROMISE_DATE);
        }
        return null;
    }

    static boolean isPastAndOpen(String promiseDate) {
        if (promiseDate == null || promiseDate.isBlank()) {
            return false;
        }
        String trimmed = promiseDate.trim();
        if (trimmed.matches("\\d{2}-\\d{2}")) {
            return PaymentDateRules.isPast(trimmed);
        }
        LocalDate d = CreditRiskParseSupport.parseInvoiceDate(trimmed);
        return d != null && d.isBefore(LocalDate.now());
    }
}
