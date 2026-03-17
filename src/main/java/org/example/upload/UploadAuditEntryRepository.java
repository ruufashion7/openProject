package org.example.upload;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface UploadAuditEntryRepository extends MongoRepository<UploadAuditEntry, String> {
    List<UploadAuditEntry> findAllByOrderByUploadedAtDesc();
}

