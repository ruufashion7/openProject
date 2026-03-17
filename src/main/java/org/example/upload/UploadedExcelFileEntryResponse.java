package org.example.upload;

import java.time.Instant;

public record UploadedExcelFileEntryResponse(
        String id,
        String type,
        String originalFilename,
        Instant uploadedAt
) {
    public static UploadedExcelFileEntryResponse from(DetailedSalesInvoicesUpload upload) {
        return new UploadedExcelFileEntryResponse(
                upload.id(),
                "detailed",
                upload.file().originalFilename(),
                upload.uploadedAt()
        );
    }

    public static UploadedExcelFileEntryResponse from(ReceivableAgeingReportUpload upload) {
        return new UploadedExcelFileEntryResponse(
                upload.id(),
                "receivable",
                upload.file().originalFilename(),
                upload.uploadedAt()
        );
    }
}

