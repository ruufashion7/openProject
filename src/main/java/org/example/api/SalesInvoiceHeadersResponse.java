package org.example.api;

import java.util.List;

public record SalesInvoiceHeadersResponse(
        String sheet,
        List<String> headers
) {
}

