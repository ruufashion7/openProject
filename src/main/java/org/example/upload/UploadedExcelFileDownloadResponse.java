package org.example.upload;

import java.time.Instant;

public record UploadedExcelFileDownloadResponse(
        String id,
        String type,
        Instant uploadedAt,
        UploadedExcelFile file
) {
    public static UploadedExcelFileDownloadResponse from(DetailedSalesInvoicesUpload upload) {
        return new UploadedExcelFileDownloadResponse(
                upload.id(),
                "detailed",
                upload.uploadedAt(),
                upload.file()
        );
    }

    public static UploadedExcelFileDownloadResponse from(ReceivableAgeingReportUpload upload) {
        return new UploadedExcelFileDownloadResponse(
                upload.id(),
                "receivable",
                upload.uploadedAt(),
                upload.file()
        );
    }
}

