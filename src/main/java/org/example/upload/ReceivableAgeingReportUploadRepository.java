package org.example.upload;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface ReceivableAgeingReportUploadRepository extends MongoRepository<ReceivableAgeingReportUpload, String> {
    ReceivableAgeingReportUpload findTopByOrderByUploadedAtDesc();
}

