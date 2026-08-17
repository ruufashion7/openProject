package org.example.bill;

import java.util.List;

public record BillExtractResponse(
        boolean ready,
        String model,
        int imagesRead,
        List<BillExtractRow> rows
) {
}
