package org.example.creditrisk;

import java.util.Locale;

final class CreditPaymentPromiseValidation {

    private static final double MAX_AMOUNT = 999_999_999_999d;
    private static final int MAX_NOTE_LEN = 200;
    private static final int MAX_DAYS = 3650;

    private CreditPaymentPromiseValidation() {
    }

    record Resolved(double promiseAmount, String voucherNo, String note) {
    }

    static Resolved resolve(
            CustomerSalesInvoiceLookupService lookup,
            CreditPaymentPromiseRepository repository,
            String customerKey,
            String customerName,
            String voucherRaw,
            Double amountRaw,
            Integer daysUntil,
            String promiseDate,
            String noteRaw
    ) {
        String voucherInput = voucherRaw != null ? voucherRaw.trim() : "";
        boolean hasVoucher = !voucherInput.isEmpty();
        boolean hasAmountInput = amountRaw != null;

        if (!hasVoucher && !hasAmountInput) {
            throw new IllegalArgumentException("Enter an invoice (voucher) number and/or amount to collect.");
        }

        boolean hasDate = promiseDate != null && !promiseDate.isBlank();
        boolean hasDays = daysUntil != null;
        if (!hasDate && !hasDays) {
            throw new IllegalArgumentException("Collect within (days) is required.");
        }
        if (hasDays && daysUntil < 0) {
            throw new IllegalArgumentException("Days until collection cannot be negative.");
        }
        if (hasDays && daysUntil > MAX_DAYS) {
            throw new IllegalArgumentException("Days until collection cannot exceed " + MAX_DAYS + ".");
        }

        String note = noteRaw != null ? noteRaw.trim() : "";
        if (note.length() > MAX_NOTE_LEN) {
            throw new IllegalArgumentException("Note is too long (max " + MAX_NOTE_LEN + " characters).");
        }

        double amount;
        String storedVoucher = null;

        if (hasVoucher) {
            CustomerSalesInvoiceLookupService.CustomerInvoiceLine line = lookup
                    .findForCustomer(customerKey, customerName, voucherInput)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Invoice not found for this customer. Check the voucher number from your latest Detailed Sales upload."));
            storedVoucher = line.voucherNo();
            if (line.currentDue() <= 0.01) {
                throw new IllegalArgumentException(
                        "That invoice has no balance due. Use another invoice or enter an amount-only reminder.");
            }
            if (hasAmountInput) {
                amount = requirePositiveAmount(amountRaw);
                if (amount > line.currentDue() + 0.01) {
                    throw new IllegalArgumentException(
                            "Amount exceeds invoice balance due (₹" + fmt(line.currentDue()) + ").");
                }
            } else {
                amount = line.currentDue();
            }
            assertNoDuplicateOpen(repository, customerKey, storedVoucher);
        } else {
            amount = requirePositiveAmount(amountRaw);
        }

        return new Resolved(amount, storedVoucher, note.isEmpty() ? "cash" : note);
    }

    private static void assertNoDuplicateOpen(
            CreditPaymentPromiseRepository repository,
            String customerKey,
            String voucherNo
    ) {
        String key = org.example.customer.CustomerIdentity.normalizeKey(customerKey);
        String norm = CustomerSalesInvoiceLookupService.normalizeVoucher(voucherNo);
        for (CreditPaymentPromiseDocument open : repository.findByCustomerKeyOrderByPromiseDateDesc(key)) {
            if (open.isFulfilled() || open.getVoucherNo() == null || open.getVoucherNo().isBlank()) {
                continue;
            }
            if (CustomerSalesInvoiceLookupService.normalizeVoucher(open.getVoucherNo()).equals(norm)) {
                throw new IllegalArgumentException(
                        "An open cash reminder already exists for invoice " + open.getVoucherNo() + ".");
            }
        }
    }

    private static double requirePositiveAmount(Double amountRaw) {
        if (amountRaw == null || !Double.isFinite(amountRaw)) {
            throw new IllegalArgumentException("Enter a valid amount greater than zero.");
        }
        if (amountRaw <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero.");
        }
        if (amountRaw > MAX_AMOUNT) {
            throw new IllegalArgumentException("Amount is too large.");
        }
        return amountRaw;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.0f", v);
    }
}
