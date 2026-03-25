package org.example.api;

public record CustomerSummaryResponse(
        String customer,
        boolean found,
        String phoneNumber,
        double totalAmount,
        boolean within45Days,
        double withinAmount,
        double midAmount,
        double beyondAmount,
        double unknownAmount,
        String nextPaymentDate,
        String whatsAppStatus,
        String customerCategory, // "semi-wholesale" | "A" | "B" | "C"
        Boolean needsFollowUp, // true if customer needs follow-up call, false otherwise
        String address, // Customer address/location
        Double latitude, // Latitude coordinate
        Double longitude, // Longitude coordinate
        String place // Station / place (customer_master)
) {
}

