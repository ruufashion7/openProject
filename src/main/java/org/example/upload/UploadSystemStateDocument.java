package org.example.upload;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Singleton document: distributed ingest lock + persisted last async upload outcome (survives restarts).
 */
@Document(collection = "upload_system_state")
public class UploadSystemStateDocument {

    public static final String SINGLETON_ID = "singleton";

    @Id
    private String id = SINGLETON_ID;

    /** Job id holding the cluster-wide ingest lock, or null. */
    private String lockJobId;
    private Instant lockExpiresAt;

    private String lastJobId;
    private String lastState;
    private String lastMessage;
    private List<UploadFileInfo> lastFiles = new ArrayList<>();
    private Instant lastCompletedAt;
    private String lastStartedByUserId;
    private String lastStartedByDisplayName;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getLockJobId() {
        return lockJobId;
    }

    public void setLockJobId(String lockJobId) {
        this.lockJobId = lockJobId;
    }

    public Instant getLockExpiresAt() {
        return lockExpiresAt;
    }

    public void setLockExpiresAt(Instant lockExpiresAt) {
        this.lockExpiresAt = lockExpiresAt;
    }

    public String getLastJobId() {
        return lastJobId;
    }

    public void setLastJobId(String lastJobId) {
        this.lastJobId = lastJobId;
    }

    public String getLastState() {
        return lastState;
    }

    public void setLastState(String lastState) {
        this.lastState = lastState;
    }

    public String getLastMessage() {
        return lastMessage;
    }

    public void setLastMessage(String lastMessage) {
        this.lastMessage = lastMessage;
    }

    public List<UploadFileInfo> getLastFiles() {
        return lastFiles;
    }

    public void setLastFiles(List<UploadFileInfo> lastFiles) {
        this.lastFiles = lastFiles != null ? lastFiles : new ArrayList<>();
    }

    public Instant getLastCompletedAt() {
        return lastCompletedAt;
    }

    public void setLastCompletedAt(Instant lastCompletedAt) {
        this.lastCompletedAt = lastCompletedAt;
    }

    public String getLastStartedByUserId() {
        return lastStartedByUserId;
    }

    public void setLastStartedByUserId(String lastStartedByUserId) {
        this.lastStartedByUserId = lastStartedByUserId;
    }

    public String getLastStartedByDisplayName() {
        return lastStartedByDisplayName;
    }

    public void setLastStartedByDisplayName(String lastStartedByDisplayName) {
        this.lastStartedByDisplayName = lastStartedByDisplayName;
    }
}
