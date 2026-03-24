package org.example.upload;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document("upload_audit_entries")
@CompoundIndex(name = "uploadAudit_uploadedAt_id_asc", def = "{'uploadedAt': 1, '_id': 1}")
public record UploadAuditEntry(
        @Id String id,
        String action,
        String type,
        String originalFilename,
        Instant uploadedAt
) {
}

