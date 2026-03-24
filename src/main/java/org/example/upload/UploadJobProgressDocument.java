package org.example.upload;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Durable job snapshot for polling from any app instance; also carries cross-instance cancel requests.
 */
@Document(collection = "upload_job_progress")
public class UploadJobProgressDocument {

    @Id
    private String jobId;
    private String state;
    private String message;
    private String phase;
    private boolean cancellable;
    private Instant startedAt;
    private String startedByUserId;
    private String startedByDisplayName;
    private List<UploadFileInfo> files = new ArrayList<>();
    /** Set via API from any instance while job is in parsing phase. */
    private boolean apiCancelRequested;

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }

    public boolean isCancellable() {
        return cancellable;
    }

    public void setCancellable(boolean cancellable) {
        this.cancellable = cancellable;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public String getStartedByUserId() {
        return startedByUserId;
    }

    public void setStartedByUserId(String startedByUserId) {
        this.startedByUserId = startedByUserId;
    }

    public String getStartedByDisplayName() {
        return startedByDisplayName;
    }

    public void setStartedByDisplayName(String startedByDisplayName) {
        this.startedByDisplayName = startedByDisplayName;
    }

    public List<UploadFileInfo> getFiles() {
        return files;
    }

    public void setFiles(List<UploadFileInfo> files) {
        this.files = files != null ? files : new ArrayList<>();
    }

    public boolean isApiCancelRequested() {
        return apiCancelRequested;
    }

    public void setApiCancelRequested(boolean apiCancelRequested) {
        this.apiCancelRequested = apiCancelRequested;
    }
}
