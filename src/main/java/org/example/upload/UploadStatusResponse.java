package org.example.upload;

public record UploadStatusResponse(
        boolean hasDetailed,
        boolean hasReceivable,
        boolean ready
) {
}

