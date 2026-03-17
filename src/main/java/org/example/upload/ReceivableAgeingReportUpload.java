package org.example.upload;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document("receivable_ageing_report_uploads")
public record ReceivableAgeingReportUpload(
        @Id String id,
        Instant uploadedAt,
        UploadedExcelFile file
) {
}

