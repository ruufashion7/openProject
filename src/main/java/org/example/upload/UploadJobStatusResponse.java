package org.example.upload;

import java.time.Instant;
import java.util.List;

/**
 * Result of {@code GET /api/upload/jobs/{jobId}} — async upload progress and outcome.
 */
public record UploadJobStatusResponse(
        String jobId,
        String state,
        String message,
        List<UploadFileInfo> files,
        String phase,
        boolean cancellable,
        String startedByUserId,
        String startedByDisplayName,
        Instant startedAt
) {
}
