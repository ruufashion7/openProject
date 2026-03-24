package org.example.upload;

import java.time.Instant;
import java.util.List;

/**
 * Last terminal async upload (success, failed, or cancelled) — kept server-side so all clients see the same message.
 */
public record UploadLastOutcomeResponse(
        String jobId,
        String state,
        String message,
        List<UploadFileInfo> files,
        Instant completedAt,
        String startedByUserId,
        String startedByDisplayName
) {
}
