package org.example.upload;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document("upload_audit_entries")
public record UploadAuditEntry(
        @Id String id,
        String action,
        String type,
        String originalFilename,
        Instant uploadedAt
) {
}

