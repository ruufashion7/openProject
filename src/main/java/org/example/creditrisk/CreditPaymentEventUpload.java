package org.example.creditrisk;

import org.example.upload.UploadedExcelFile;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** Latest Customer Ledger Excel for credit-risk payment / bounce metrics. */
@Document(collection = "credit_payment_event_uploads")
public record CreditPaymentEventUpload(
        @Id String id,
        Instant uploadedAt,
        UploadedExcelFile file,
        String uploadedBy,
        /** {@link CustomerLedgerExcelUploadValidation#FORMAT_CUSTOMER_LEDGER} */
        String format,
        Integer dataRowCount
) {
}
