package org.example.api;

public record PaymentDateCustomerCard(
        String customer,
        double totalAmount,
        String nextPaymentDate,
        String phoneNumber,
        String whatsAppStatus, // "not sent" | "sent" | "delivered"
        String customerCategory, // "semi-wholesale" | "A" | "B" | "C"
        String lastOrderDate, // Most recent invoice date from SalesInvoiceMaster
        Boolean needsFollowUp, // true if customer needs follow-up call, false otherwise
        String address, // Customer address
        Double latitude, // Customer location latitude
        Double longitude, // Customer location longitude
        String place, // Place/station e.g. "Mumbai local station"
        /** Receivable ageing bucket amounts — same source as Customer Details summary. */
        double withinAmount,
        double midAmount,
        double beyondAmount,
        /** True when customer is on the shared Retain list (shown even with ₹0). */
        Boolean retained,
        Double creditLimitOverride,
        Double effectiveCreditLimit,
        String creditLimitSource,
        boolean overCreditLimit,
        Double creditLimitUtilization
) {
}
