package org.example.upload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * MongoDB-backed distributed ingest lock, durable job progress (any instance can poll), and persisted last outcome.
 * The singleton system-state document is created lazily on first lock or outcome write (not at bean init), so the app
 * can start when MongoDB is temporarily unreachable.
 */
@Service
public class UploadAsyncPersistenceService {
    private static final Logger logger = LoggerFactory.getLogger(UploadAsyncPersistenceService.class);

    private final MongoTemplate mongoTemplate;
    private final UploadSystemStateRepository systemStateRepository;
    private final UploadJobProgressRepository progressRepository;

    public UploadAsyncPersistenceService(
            MongoTemplate mongoTemplate,
            UploadSystemStateRepository systemStateRepository,
            UploadJobProgressRepository progressRepository
    ) {
        this.mongoTemplate = mongoTemplate;
        this.systemStateRepository = systemStateRepository;
        this.progressRepository = progressRepository;
    }

    private void ensureSingleton() {
        if (!systemStateRepository.existsById(UploadSystemStateDocument.SINGLETON_ID)) {
            try {
                UploadSystemStateDocument s = new UploadSystemStateDocument();
                s.setId(UploadSystemStateDocument.SINGLETON_ID);
                systemStateRepository.save(s);
            } catch (DuplicateKeyException ex) {
                logger.debug("upload_system_state singleton already created concurrently.");
            }
        }
    }

    /**
     * @return true if this JVM acquired the cluster-wide lock for {@code jobId}.
     */
    public boolean tryAcquireDistributedLock(String jobId, Duration lease) {
        ensureSingleton();
        Instant now = Instant.now();
        Instant until = now.plus(lease);
        Criteria freeOrExpired = new Criteria().orOperator(
                Criteria.where("lockJobId").exists(false),
                Criteria.where("lockJobId").is(null),
                Criteria.where("lockExpiresAt").lt(now)
        );
        Query q = new Query(Criteria.where("_id").is(UploadSystemStateDocument.SINGLETON_ID)).addCriteria(freeOrExpired);
        Update u = new Update().set("lockJobId", jobId).set("lockExpiresAt", until);
        return mongoTemplate.updateFirst(q, u, UploadSystemStateDocument.class).getModifiedCount() > 0;
    }

    public void releaseDistributedLock(String jobId) {
        Query q = new Query(Criteria.where("_id").is(UploadSystemStateDocument.SINGLETON_ID).and("lockJobId").is(jobId));
        Update u = new Update().unset("lockJobId").set("lockExpiresAt", Instant.now());
        mongoTemplate.updateFirst(q, u, UploadSystemStateDocument.class);
    }

    public Optional<String> getCurrentLockJobId() {
        return systemStateRepository.findById(UploadSystemStateDocument.SINGLETON_ID)
                .map(UploadSystemStateDocument::getLockJobId)
                .filter(id -> id != null && !id.isBlank());
    }

    public boolean isDistributedLockActive(Instant now) {
        return systemStateRepository.findById(UploadSystemStateDocument.SINGLETON_ID)
                .filter(s -> s.getLockJobId() != null && !s.getLockJobId().isBlank())
                .filter(s -> s.getLockExpiresAt() != null && s.getLockExpiresAt().isAfter(now))
                .isPresent();
    }

    /**
     * If the distributed lock is past expiry, clear it and mark the job failed (any instance may run this).
     */
    public boolean clearExpiredDistributedLock(Instant now, Consumer<UploadLastOutcomeResponse> onOutcome) {
        UploadSystemStateDocument s = systemStateRepository.findById(UploadSystemStateDocument.SINGLETON_ID).orElse(null);
        if (s == null || s.getLockJobId() == null || s.getLockExpiresAt() == null) {
            return false;
        }
        if (s.getLockExpiresAt().isAfter(now)) {
            return false;
        }
        String jid = s.getLockJobId();
        Query q = new Query(Criteria.where("_id").is(UploadSystemStateDocument.SINGLETON_ID).and("lockJobId").is(jid));
        Update u = new Update().unset("lockJobId").set("lockExpiresAt", now);
        if (mongoTemplate.updateFirst(q, u, UploadSystemStateDocument.class).getModifiedCount() == 0) {
            return false;
        }
        progressRepository.findById(jid).ifPresent(p -> {
            if ("processing".equals(p.getState())) {
                p.setState("failed");
                p.setMessage("Upload lock lease expired before completion; please try again.");
                p.setPhase(null);
                p.setCancellable(false);
                progressRepository.save(p);
            }
        });
        UploadLastOutcomeResponse outcome = new UploadLastOutcomeResponse(
                jid,
                "failed",
                "Upload lock lease expired before completion; please try again.",
                List.of(),
                now,
                "",
                ""
        );
        persistLastOutcomeFields(outcome);
        onOutcome.accept(outcome);
        logger.warn("Cleared expired distributed upload lock for jobId={}", jid);
        return true;
    }

    public void persistLastOutcome(UploadLastOutcomeResponse outcome) {
        persistLastOutcomeFields(outcome);
    }

    private void persistLastOutcomeFields(UploadLastOutcomeResponse o) {
        ensureSingleton();
        Query q = new Query(Criteria.where("_id").is(UploadSystemStateDocument.SINGLETON_ID));
        Update u = new Update()
                .set("lastJobId", o.jobId())
                .set("lastState", o.state())
                .set("lastMessage", o.message())
                .set("lastFiles", o.files() == null ? List.of() : o.files())
                .set("lastCompletedAt", o.completedAt())
                .set("lastStartedByUserId", o.startedByUserId() == null ? "" : o.startedByUserId())
                .set("lastStartedByDisplayName", o.startedByDisplayName() == null ? "" : o.startedByDisplayName());
        mongoTemplate.updateFirst(q, u, UploadSystemStateDocument.class);
    }

    public Optional<UploadLastOutcomeResponse> loadLastOutcome() {
        return systemStateRepository.findById(UploadSystemStateDocument.SINGLETON_ID)
                .filter(s -> s.getLastJobId() != null && !s.getLastJobId().isBlank())
                .map(s -> new UploadLastOutcomeResponse(
                        s.getLastJobId(),
                        s.getLastState() == null ? "unknown" : s.getLastState(),
                        s.getLastMessage() == null ? "" : s.getLastMessage(),
                        s.getLastFiles() == null ? List.of() : s.getLastFiles(),
                        s.getLastCompletedAt(),
                        s.getLastStartedByUserId() == null ? "" : s.getLastStartedByUserId(),
                        s.getLastStartedByDisplayName() == null ? "" : s.getLastStartedByDisplayName()
                ));
    }

    public void saveJobProgress(UploadJobProgressDocument doc) {
        progressRepository.save(doc);
    }

    public Optional<UploadJobProgressDocument> findJobProgress(String jobId) {
        return progressRepository.findById(jobId);
    }

    public void deleteJobProgress(String jobId) {
        progressRepository.deleteById(jobId);
    }

    public void setApiCancelRequested(String jobId, boolean requested) {
        UploadJobProgressDocument p = progressRepository.findById(jobId).orElseGet(() -> {
            UploadJobProgressDocument n = new UploadJobProgressDocument();
            n.setJobId(jobId);
            n.setState("processing");
            n.setMessage("Cancel requested");
            n.setStartedAt(Instant.now());
            return n;
        });
        p.setApiCancelRequested(requested);
        progressRepository.save(p);
    }

    public boolean isApiCancelRequested(String jobId) {
        return progressRepository.findById(jobId).map(UploadJobProgressDocument::isApiCancelRequested).orElse(false);
    }

    public void forceClearDistributedLock() {
        Query q = new Query(Criteria.where("_id").is(UploadSystemStateDocument.SINGLETON_ID));
        Update u = new Update().unset("lockJobId").set("lockExpiresAt", Instant.now());
        mongoTemplate.updateFirst(q, u, UploadSystemStateDocument.class);
    }
}
