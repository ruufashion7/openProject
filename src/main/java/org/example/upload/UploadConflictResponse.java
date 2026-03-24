package org.example.upload;

/**
 * Returned with HTTP 409 when an upload is already running — includes the active job id for polling/cancel.
 */
public record UploadConflictResponse(String status, String message, String currentJobId) {
}
