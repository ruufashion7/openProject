package org.example.upload;

import org.example.security.SecurityAuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs Excel ingest off the HTTP thread so proxies do not time out (502) during long MongoDB writes.
 * At most one upload runs at a time; the next upload may start only after success, failure, or cancel.
 */
@Service
public class UploadJobService {
    private static final Logger logger = LoggerFactory.getLogger(UploadJobService.class);

    private static final String PHASE_PARSING = "parsing";
    private static final String PHASE_SAVING = "saving";

    private final UploadStorageService uploadStorageService;
    private final SecurityAuditService securityAuditService;
    private final ThreadPoolTaskExecutor uploadExecutor;
    private final UploadAsyncPersistenceService persistence;

    private final Map<String, UploadJob> jobs = new ConcurrentHashMap<>();
    private final AtomicBoolean uploadInProgress = new AtomicBoolean(false);
    private final AtomicReference<Future<?>> currentUploadFuture = new AtomicReference<>();

    @Value("${upload.async.max-duration-minutes:120}")
    private int maxDurationMinutes;

    @Value("${upload.async.grace-after-max-minutes:5}")
    private int graceAfterMaxMinutes;

    @Value("${upload.distributed-lock-lease-hours:4}")
    private int distributedLockLeaseHours;

    /** Last terminal outcome — mirrored to MongoDB (survives restarts and is shared across instances). */
    private volatile UploadLastOutcomeResponse lastOutcome;

    public UploadJobService(
            UploadStorageService uploadStorageService,
            SecurityAuditService securityAuditService,
            @Qualifier("uploadTaskExecutor") ThreadPoolTaskExecutor uploadExecutor,
            UploadAsyncPersistenceService persistence
    ) {
        this.uploadStorageService = uploadStorageService;
        this.securityAuditService = securityAuditService;
        this.uploadExecutor = uploadExecutor;
        this.persistence = persistence;
    }

    /**
     * Called on a schedule to interrupt jobs that exceed {@link #maxDurationMinutes} and, if still stuck,
     * force-release the upload lock after a grace period (so the system cannot stay stuck forever).
     */
    public void onWatchdogTick() {
        Instant now = Instant.now();
        persistence.clearExpiredDistributedLock(now, o -> lastOutcome = o);

        UploadJob job = findProcessingJob();
        Optional<String> mongoLock = persistence.getCurrentLockJobId();

        if (job == null || mongoLock.isEmpty() || !job.id.equals(mongoLock.get())) {
            if (uploadInProgress.get() && job == null) {
                logger.warn("Watchdog: clearing orphan local upload flag (no processing job on this instance).");
                uploadInProgress.set(false);
                currentUploadFuture.set(null);
            }
            if (job != null && mongoLock.isPresent() && !job.id.equals(mongoLock.get())) {
                logger.warn(
                        "Watchdog: local job {} does not match distributed lock {}; skipping interrupt on this instance.",
                        job.id,
                        mongoLock.get()
                );
            }
            return;
        }

        if (!uploadInProgress.get()) {
            return;
        }

        long minutes = Duration.between(job.createdAt, now).toMinutes();
        long softThreshold = maxDurationMinutes;
        long hardThreshold = (long) maxDurationMinutes + graceAfterMaxMinutes;

        if (minutes >= softThreshold && !job.watchdogSoftCancelSent) {
            job.watchdogSoftCancelSent = true;
            job.watchdogTimedOut = true;
            job.cancelRequested = true;
            job.message = "Upload exceeded maximum time; stopping…";
            persistJobSnapshot(job);
            Future<?> f = currentUploadFuture.get();
            if (f != null && !f.isDone()) {
                boolean cancelled = f.cancel(true);
                logger.warn("Watchdog: upload exceeded {} minutes; cancel(interrupt)={} jobId={}", softThreshold, cancelled, job.id);
            }
        }

        if (minutes >= hardThreshold) {
            Future<?> f = currentUploadFuture.get();
            if (f != null && !f.isDone()) {
                logger.error(
                        "Watchdog: upload still running after {} minutes (grace + {}); forcing lock release. jobId={}",
                        hardThreshold,
                        graceAfterMaxMinutes,
                        job.id
                );
                forceAbandonJob(job);
            } else if (uploadInProgress.get() && "processing".equals(job.state)) {
                logger.error(
                        "Watchdog: hard threshold reached but future is done while lock still held — forcing release. jobId={}",
                        job.id
                );
                forceAbandonJob(job);
            }
        }
    }

    private void forceAbandonJob(UploadJob job) {
        synchronized (job) {
            if (job.abandoned) {
                return;
            }
            job.abandoned = true;
            job.watchdogTimedOut = true;
            job.state = "failed";
            job.phase = null;
            job.message = "Upload timed out or was stuck; the server released the lock. Please try again. "
                    + "If the problem persists, contact support.";
            job.completedAt = Instant.now();
            setLastOutcome(job);
        }
        uploadInProgress.set(false);
        currentUploadFuture.set(null);
        persistence.releaseDistributedLock(job.id);
    }

    public UploadAsyncStateResponse getAsyncState() {
        Instant now = Instant.now();
        boolean busy = persistence.isDistributedLockActive(now);
        UploadCurrentJobResponse current = null;
        if (busy) {
            Optional<String> jid = persistence.getCurrentLockJobId();
            if (jid.isPresent()) {
                UploadJob local = jobs.get(jid.get());
                if (local != null) {
                    current = toCurrentResponse(local);
                } else {
                    current = persistence.findJobProgress(jid.get())
                            .map(this::toCurrentFromProgressDoc)
                            .orElseGet(() -> remotePlaceholderCurrent(jid.get()));
                }
            }
        }
        UploadLastOutcomeResponse outcome = lastOutcome != null ? lastOutcome : persistence.loadLastOutcome().orElse(null);
        return new UploadAsyncStateResponse(busy, current, outcome);
    }

    /**
     * @return job id if started; empty with {@link StartJobOutcome#blockedByJobId()} when another upload is running
     */
    public StartJobOutcome startJob(
            String userId,
            String displayName,
            Path tempFile1,
            Path tempFile2,
            String originalFilename1,
            String originalFilename2,
            long size1,
            long size2
    ) {
        pruneCompletedJobs();
        String jobId = UUID.randomUUID().toString();
        if (!persistence.tryAcquireDistributedLock(jobId, Duration.ofHours(distributedLockLeaseHours))) {
            deleteQuietly(tempFile1);
            deleteQuietly(tempFile2);
            String blocker = persistence.getCurrentLockJobId().orElse(null);
            if (blocker == null) {
                blocker = findProcessingJobId();
            }
            return StartJobOutcome.blocked(blocker);
        }
        if (!uploadInProgress.compareAndSet(false, true)) {
            persistence.releaseDistributedLock(jobId);
            deleteQuietly(tempFile1);
            deleteQuietly(tempFile2);
            return StartJobOutcome.blocked(findProcessingJobId());
        }

        UploadJob job = new UploadJob(jobId, userId, displayName, Instant.now());
        jobs.put(jobId, job);
        job.state = "processing";
        job.phase = PHASE_PARSING;
        job.message = "Reading and parsing Excel files…";
        persistJobSnapshot(job);

        try {
            Future<?> future = uploadExecutor.submit(
                    () -> runJob(
                            job,
                            tempFile1,
                            tempFile2,
                            originalFilename1,
                            originalFilename2,
                            size1,
                            size2
                    )
            );
            currentUploadFuture.set(future);
        } catch (RuntimeException ex) {
            jobs.remove(jobId);
            persistence.releaseDistributedLock(jobId);
            uploadInProgress.set(false);
            deleteQuietly(tempFile1);
            deleteQuietly(tempFile2);
            throw ex;
        }

        return StartJobOutcome.started(jobId);
    }

    public UploadJobPollResult pollJob(String jobId) {
        UploadJob job = jobs.get(jobId);
        if (job != null) {
            return UploadJobPollResult.ok(toStatusResponse(job));
        }
        return persistence.findJobProgress(jobId)
                .map(d -> UploadJobPollResult.ok(toStatusFromProgressDoc(d)))
                .orElse(UploadJobPollResult.notFound());
    }

    /**
     * Any user with upload permission may cancel while the job is in {@code parsing} phase only.
     */
    public CancelRequestResult requestCancel(String jobId, String requestedByUserId) {
        Optional<UploadJobProgressDocument> docOpt = persistence.findJobProgress(jobId);
        UploadJob local = jobs.get(jobId);
        if (local == null && docOpt.isEmpty()) {
            return CancelRequestResult.notFound();
        }
        boolean saving = (local != null && PHASE_SAVING.equals(local.phase))
                || (docOpt.isPresent() && PHASE_SAVING.equals(docOpt.get().getPhase()));
        if (saving) {
            return CancelRequestResult.cannotCancelWhileSaving();
        }
        boolean active = local != null
                ? "processing".equals(local.state)
                : "processing".equals(docOpt.map(UploadJobProgressDocument::getState).orElse(""));
        if (!active) {
            return CancelRequestResult.notActive();
        }
        persistence.setApiCancelRequested(jobId, true);
        if (local != null) {
            local.cancelRequested = true;
            local.message = "Stopping…";
            persistJobSnapshot(local);
        } else {
            docOpt.ifPresent(d -> {
                d.setMessage("Stopping…");
                persistence.saveJobProgress(d);
            });
        }
        logger.info("Upload cancel requested. jobId={} requestedBy={}", jobId, requestedByUserId);
        return CancelRequestResult.accepted();
    }

    private void runJob(
            UploadJob job,
            Path tempFile1,
            Path tempFile2,
            String originalFilename1,
            String originalFilename2,
            long size1,
            long size2
    ) {
        UploadCancelChecker checker = () -> {
            if (job.abandoned) {
                throw new UploadCancellationException();
            }
            if (persistence.isApiCancelRequested(job.id)) {
                throw new UploadCancellationException();
            }
            if (job.cancelRequested || job.watchdogTimedOut) {
                throw new UploadCancellationException();
            }
            if (Thread.currentThread().isInterrupted()) {
                job.watchdogTimedOut = true;
                throw new UploadCancellationException();
            }
        };
        try {
            List<UploadFileInfo> stored = uploadStorageService.storeFiles(
                    tempFile1,
                    tempFile2,
                    originalFilename1,
                    originalFilename2,
                    checker,
                    () -> {
                        if (job.abandoned) {
                            return;
                        }
                        job.phase = PHASE_SAVING;
                        job.message = "Replacing previous data and saving to the database…";
                        persistJobSnapshot(job);
                    }
            );
            if (job.abandoned) {
                return;
            }
            job.files = stored;
            job.state = "success";
            job.phase = null;
            job.message = "Files uploaded successfully.";
            job.completedAt = Instant.now();
            setLastOutcome(job);

            securityAuditService.logFileUpload(job.userId, originalFilename1, size1, true);
            securityAuditService.logFileUpload(job.userId, originalFilename2, size2, true);
        } catch (UploadCancellationException ex) {
            if (job.abandoned) {
                return;
            }
            if (job.watchdogTimedOut) {
                job.state = "failed";
                job.phase = null;
                job.message = "Upload exceeded the maximum allowed time and was stopped.";
                job.completedAt = Instant.now();
                setLastOutcome(job);
                securityAuditService.logFileUpload(job.userId, originalFilename1, size1, false);
                securityAuditService.logFileUpload(job.userId, originalFilename2, size2, false);
                logger.warn("Upload timed out (soft). jobId={}", job.id);
            } else {
                job.state = "cancelled";
                job.phase = null;
                job.message = "Upload was stopped before new data was saved. Previous uploaded data is unchanged.";
                job.completedAt = Instant.now();
                setLastOutcome(job);
                securityAuditService.logFileUpload(job.userId, originalFilename1, size1, false);
                securityAuditService.logFileUpload(job.userId, originalFilename2, size2, false);
                logger.info("Upload cancelled. jobId={}", job.id);
            }
        } catch (IllegalArgumentException ex) {
            if (job.abandoned) {
                return;
            }
            logger.warn("Async upload rejected. {}", ex.getMessage());
            job.state = "failed";
            job.phase = null;
            job.message = ex.getMessage();
            job.completedAt = Instant.now();
            setLastOutcome(job);
            securityAuditService.logFileUpload(job.userId, originalFilename1, size1, false);
            securityAuditService.logFileUpload(job.userId, originalFilename2, size2, false);
        } catch (org.bson.BsonMaximumSizeExceededException ex) {
            if (job.abandoned) {
                return;
            }
            logger.error("Async upload failed: MongoDB document size limit.", ex);
            failJobTooLarge(job, originalFilename1, originalFilename2, size1, size2);
        } catch (DataAccessException ex) {
            if (job.abandoned) {
                return;
            }
            logger.error("Async upload failed due to database error.", ex);
            String errorMessage = "Upload failed due to database error.";
            Throwable cause = ex.getCause();
            if (cause != null) {
                String causeMessage = cause.getMessage();
                if (cause instanceof org.bson.BsonMaximumSizeExceededException
                        || (causeMessage != null && (causeMessage.contains("16777216")
                        || causeMessage.contains("BsonMaximumSizeExceededException")))) {
                    errorMessage = "File is too large. The Excel file contains too much data (exceeds MongoDB's 16MB document limit). "
                            + "Please reduce the number of rows or split the data into smaller files.";
                }
            }
            job.state = "failed";
            job.phase = null;
            job.message = errorMessage;
            job.completedAt = Instant.now();
            setLastOutcome(job);
            securityAuditService.logFileUpload(job.userId, originalFilename1, size1, false);
            securityAuditService.logFileUpload(job.userId, originalFilename2, size2, false);
        } catch (IOException ex) {
            if (job.abandoned) {
                return;
            }
            logger.error("Async upload failed while processing files.", ex);
            job.state = "failed";
            job.phase = null;
            job.message = "Upload failed. Please check the file format and try again.";
            job.completedAt = Instant.now();
            setLastOutcome(job);
            securityAuditService.logFileUpload(job.userId, originalFilename1, size1, false);
            securityAuditService.logFileUpload(job.userId, originalFilename2, size2, false);
        } catch (Exception ex) {
            if (job.abandoned) {
                return;
            }
            logger.error("Async upload failed with unexpected error.", ex);
            job.state = "failed";
            job.phase = null;
            job.message = "Upload failed. Please try again or contact support if the problem persists.";
            job.completedAt = Instant.now();
            setLastOutcome(job);
            securityAuditService.logFileUpload(job.userId, originalFilename1, size1, false);
            securityAuditService.logFileUpload(job.userId, originalFilename2, size2, false);
        } finally {
            deleteQuietly(tempFile1);
            deleteQuietly(tempFile2);
            currentUploadFuture.set(null);
            persistJobSnapshot(job);
            persistence.releaseDistributedLock(job.id);
            if (!job.abandoned) {
                uploadInProgress.set(false);
            }
        }
    }

    private void setLastOutcome(UploadJob job) {
        UploadLastOutcomeResponse r = new UploadLastOutcomeResponse(
                job.id,
                job.state,
                job.message,
                job.files == null ? List.of() : job.files,
                job.completedAt,
                job.userId,
                job.displayName
        );
        lastOutcome = r;
        persistence.persistLastOutcome(r);
        persistJobSnapshot(job);
    }

    private void failJobTooLarge(UploadJob job, String name1, String name2, long size1, long size2) {
        if (job.abandoned) {
            return;
        }
        job.state = "failed";
        job.phase = null;
        job.message = "File is too large. The Excel file contains too much data (exceeds MongoDB's 16MB document limit). "
                + "Please reduce the number of rows or split the data into smaller files.";
        job.completedAt = Instant.now();
        setLastOutcome(job);
        securityAuditService.logFileUpload(job.userId, name1, size1, false);
        securityAuditService.logFileUpload(job.userId, name2, size2, false);
    }

    private UploadJobStatusResponse toStatusResponse(UploadJob job) {
        boolean cancellable = "processing".equals(job.state) && PHASE_PARSING.equals(job.phase) && !job.cancelRequested;
        return new UploadJobStatusResponse(
                job.id,
                job.state,
                job.message,
                job.files == null ? List.of() : job.files,
                job.phase,
                cancellable,
                job.userId,
                job.displayName,
                job.createdAt
        );
    }

    private UploadCurrentJobResponse toCurrentResponse(UploadJob job) {
        boolean cancellable = "processing".equals(job.state) && PHASE_PARSING.equals(job.phase) && !job.cancelRequested;
        return new UploadCurrentJobResponse(
                job.id,
                job.state,
                job.message,
                job.phase,
                cancellable,
                job.createdAt,
                job.userId,
                job.displayName
        );
    }

    private UploadJob findProcessingJob() {
        return jobs.values().stream()
                .filter(j -> "processing".equals(j.state))
                .findFirst()
                .orElse(null);
    }

    private String findProcessingJobId() {
        UploadJob j = findProcessingJob();
        return j == null ? null : j.id;
    }

    private void pruneCompletedJobs() {
        Instant cutoff = Instant.now().minus(24, ChronoUnit.HOURS);
        jobs.entrySet().removeIf(e -> {
            UploadJob j = e.getValue();
            boolean done = ("success".equals(j.state) || "failed".equals(j.state) || "cancelled".equals(j.state))
                    && j.completedAt != null
                    && j.completedAt.isBefore(cutoff);
            if (done) {
                persistence.deleteJobProgress(e.getKey());
            }
            return done;
        });
    }

    private void persistJobSnapshot(UploadJob job) {
        UploadJobProgressDocument d = persistence.findJobProgress(job.id).orElseGet(UploadJobProgressDocument::new);
        d.setJobId(job.id);
        d.setState(job.state);
        d.setMessage(job.message != null ? job.message : "");
        d.setPhase(job.phase);
        d.setCancellable("processing".equals(job.state) && PHASE_PARSING.equals(job.phase) && !job.cancelRequested);
        d.setStartedAt(job.createdAt);
        d.setStartedByUserId(job.userId);
        d.setStartedByDisplayName(job.displayName);
        if (job.files != null) {
            d.setFiles(job.files);
        }
        if (job.cancelRequested) {
            d.setApiCancelRequested(true);
        }
        persistence.saveJobProgress(d);
    }

    private UploadCurrentJobResponse toCurrentFromProgressDoc(UploadJobProgressDocument d) {
        return new UploadCurrentJobResponse(
                d.getJobId(),
                d.getState(),
                d.getMessage(),
                d.getPhase(),
                d.isCancellable(),
                d.getStartedAt() != null ? d.getStartedAt() : Instant.now(),
                d.getStartedByUserId() != null ? d.getStartedByUserId() : "",
                d.getStartedByDisplayName() != null ? d.getStartedByDisplayName() : ""
        );
    }

    private UploadCurrentJobResponse remotePlaceholderCurrent(String jobId) {
        return new UploadCurrentJobResponse(
                jobId,
                "processing",
                "Upload in progress on another instance (this server is not running the worker). Poll this job id for status.",
                null,
                false,
                Instant.now(),
                "",
                ""
        );
    }

    private UploadJobStatusResponse toStatusFromProgressDoc(UploadJobProgressDocument d) {
        return new UploadJobStatusResponse(
                d.getJobId(),
                d.getState(),
                d.getMessage(),
                d.getFiles() == null ? List.of() : d.getFiles(),
                d.getPhase(),
                d.isCancellable(),
                d.getStartedByUserId() != null ? d.getStartedByUserId() : "",
                d.getStartedByDisplayName() != null ? d.getStartedByDisplayName() : "",
                d.getStartedAt() != null ? d.getStartedAt() : Instant.now()
        );
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            LoggerFactory.getLogger(UploadJobService.class).warn("Could not delete temp file {}", path, ex);
        }
    }

    public record StartJobOutcome(Optional<String> jobId, Optional<String> blockedByJobId) {
        public static StartJobOutcome started(String id) {
            return new StartJobOutcome(Optional.of(id), Optional.empty());
        }

        public static StartJobOutcome blocked(String currentJobIdOrNull) {
            return new StartJobOutcome(Optional.empty(), Optional.ofNullable(currentJobIdOrNull));
        }
    }

    public sealed interface CancelRequestResult {
        record Accepted() implements CancelRequestResult {}
        record NotFound() implements CancelRequestResult {}
        record NotActive() implements CancelRequestResult {}
        record CannotCancelWhileSaving() implements CancelRequestResult {}

        static CancelRequestResult accepted() {
            return new Accepted();
        }
        static CancelRequestResult notFound() {
            return new NotFound();
        }
        static CancelRequestResult notActive() {
            return new NotActive();
        }
        static CancelRequestResult cannotCancelWhileSaving() {
            return new CannotCancelWhileSaving();
        }
    }

    private static final class UploadJob {
        final String id;
        final String userId;
        final String displayName;
        final Instant createdAt;
        volatile Instant completedAt;
        volatile String state;
        volatile String phase;
        volatile String message;
        volatile List<UploadFileInfo> files;
        volatile boolean cancelRequested;
        /** Watchdog: soft interrupt sent (Future.cancel). */
        volatile boolean watchdogSoftCancelSent;
        /** True when the watchdog stops the job for exceeding max duration. */
        volatile boolean watchdogTimedOut;
        /** Hard abandon: watchdog released lock; worker must not overwrite lastOutcome with success. */
        volatile boolean abandoned;

        UploadJob(String id, String userId, String displayName, Instant createdAt) {
            this.id = id;
            this.userId = userId;
            this.displayName = displayName == null ? "" : displayName;
            this.createdAt = createdAt;
        }
    }
}
