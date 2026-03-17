package org.example.api;

import java.util.List;
import java.util.Map;

public record CustomerLedgerDebugResponse(
        String sheet,
        List<String> customerHeaders,
        List<String> invoiceHeaders,
        List<String> voucherHeaders,
        List<String> receivedHeaders,
        List<String> dueHeaders,
        Map<String, String> sampleRow
) {
    public static CustomerLedgerDebugResponse empty() {
        return new CustomerLedgerDebugResponse(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of()
        );
    }
}

