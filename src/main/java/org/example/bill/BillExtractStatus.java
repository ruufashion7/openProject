package org.example.bill;

public record BillExtractStatus(
        boolean enabled,
        boolean llmConfigured,
        boolean ready,
        String model,
        String setupHint
) {
}
