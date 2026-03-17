package org.example.api;

import java.util.List;

public record SalesInvoicePageResponse(
        List<SalesInvoiceEntry> content,
        int totalElements,
        int totalPages,
        int currentPage,
        int pageSize
) {
}

