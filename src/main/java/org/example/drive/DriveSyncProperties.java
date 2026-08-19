package org.example.drive;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Google Drive Excel → next payment date sync. Disabled until file id + service account are set.
 */
@Component
public class DriveSyncProperties {

    private final boolean enabled;
    private final String fileIdRaw;
    private final String serviceAccountJson;
    private final String sheetName;
    private final boolean writeBackEnabled;
    private final int maxUnmatchedStored;

    public DriveSyncProperties(
            @Value("${google.drive.sync.enabled:false}") boolean enabled,
            @Value("${google.drive.sync.file-id:}") String fileIdRaw,
            @Value("${google.drive.sync.service-account-json:}") String serviceAccountJson,
            @Value("${google.drive.sync.sheet-name:}") String sheetName,
            @Value("${google.drive.sync.write-back-enabled:true}") boolean writeBackEnabled,
            @Value("${google.drive.sync.max-unmatched-stored:200}") int maxUnmatchedStored
    ) {
        this.enabled = enabled;
        this.fileIdRaw = fileIdRaw == null ? "" : fileIdRaw.trim();
        this.serviceAccountJson = serviceAccountJson == null ? "" : serviceAccountJson.trim();
        this.sheetName = sheetName == null ? "" : sheetName.trim();
        this.writeBackEnabled = writeBackEnabled;
        this.maxUnmatchedStored = Math.max(20, maxUnmatchedStored);
    }

    public boolean enabled() {
        return enabled;
    }

    public String fileIdRaw() {
        return fileIdRaw;
    }

    public String serviceAccountJson() {
        return serviceAccountJson;
    }

    public String sheetName() {
        return sheetName;
    }

    public boolean writeBackEnabled() {
        return writeBackEnabled;
    }

    public int maxUnmatchedStored() {
        return maxUnmatchedStored;
    }

    public boolean isConfigured() {
        return enabled && !extractFileId().isBlank() && !serviceAccountJson.isBlank();
    }

    /**
     * Accepts a bare Drive file id or a full Drive URL.
     */
    public String extractFileId() {
        if (fileIdRaw.isBlank()) {
            return "";
        }
        java.util.regex.Matcher path = java.util.regex.Pattern.compile("/d/([a-zA-Z0-9_-]+)").matcher(fileIdRaw);
        if (path.find()) {
            return path.group(1);
        }
        java.util.regex.Matcher query = java.util.regex.Pattern.compile("[?&]id=([a-zA-Z0-9_-]+)").matcher(fileIdRaw);
        if (query.find()) {
            return query.group(1);
        }
        return fileIdRaw;
    }
}
