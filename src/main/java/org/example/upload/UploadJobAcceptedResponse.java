package org.example.upload;

/**
 * Body for {@code 202 Accepted} from {@code POST /api/upload} when processing runs in the background.
 */
public record UploadJobAcceptedResponse(String jobId, String message) {
}
