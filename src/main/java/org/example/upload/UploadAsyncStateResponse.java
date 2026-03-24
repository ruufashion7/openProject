package org.example.upload;

/**
 * Global async upload state: whether work is running, current job (if any), and last finished outcome.
 */
public record UploadAsyncStateResponse(
        boolean busy,
        UploadCurrentJobResponse currentJob,
        UploadLastOutcomeResponse lastOutcome
) {
}
