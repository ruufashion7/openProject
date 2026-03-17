package org.example.api.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for sales invoice search operations.
 * SECURITY: Sensitive data (customer names, phone numbers, voucher numbers) should be sent in POST body, not URL.
 */
public record SalesInvoiceSearchRequest(
        @Size(max = 200, message = "Customer name must not exceed 200 characters")
        String customer,
        
        @Size(max = 20, message = "Phone number must not exceed 20 characters")
        String phone,
        
        @Size(max = 100, message = "Voucher number must not exceed 100 characters")
        String voucherNo,
        
        String dateFrom,
        String dateTo,
        Double receivedAmountMin,
        Double receivedAmountMax,
        Double currentDueMin,
        Double currentDueMax,
        Integer ageingDaysMin,
        Integer ageingDaysMax,
        String ageingBucket,
        Double totalAmountMin,
        Double totalAmountMax,
        Integer year,
        @Min(1) @Max(12)
        Integer month,
        @Min(1) @Max(4)
        Integer quarter,
        String status,
        String sortBy,
        @Size(max = 10)
        String sortOrder,
        @Min(0)
        Integer page,
        @Min(1) @Max(100)
        Integer size
) {
    public SalesInvoiceSearchRequest {
        // Default values
        if (sortOrder == null) {
            sortOrder = "asc";
        }
        if (page == null) {
            page = 0;
        }
        if (size == null) {
            size = 15;
        }
    }
}

