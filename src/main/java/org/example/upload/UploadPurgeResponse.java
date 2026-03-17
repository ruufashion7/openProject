package org.example.upload;

public record UploadPurgeResponse(
        long detailedDeleted,
        long receivableDeleted
) {
}

