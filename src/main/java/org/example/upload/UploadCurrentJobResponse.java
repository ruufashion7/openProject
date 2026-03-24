package org.example.upload;

import java.time.Instant;

/** In-flight upload job visible to anyone with upload permission. */
public record UploadCurrentJobResponse(
        String jobId,
        String state,
        String message,
        String phase,
        boolean cancellable,
        Instant startedAt,
        String startedByUserId,
        String startedByDisplayName
) {
}
