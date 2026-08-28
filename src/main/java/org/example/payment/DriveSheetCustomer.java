package org.example.payment;

/**
 * One customer row for the Drive payment sheet, with receivable total for sorting.
 */
public record DriveSheetCustomer(
        PaymentDateOverride customer,
        double outstandingAmount
) {
    public String customerKey() {
        return customer == null ? "" : customer.customerKey();
    }

    public boolean retained() {
        return customer != null && customer.isRetained();
    }
}
