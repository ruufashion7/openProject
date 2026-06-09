package org.example.model;

public record DashboardCustomerStats(
        long totalRecords,
        long activeInUpload,
        long withPhone,
        long withLocation
) {
}
