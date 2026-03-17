package org.example.upload;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document("detailed_sales_invoices_uploads")
public record DetailedSalesInvoicesUpload(
        @Id String id,
        Instant uploadedAt,
        UploadedExcelFile file
) {
}

