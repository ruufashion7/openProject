package org.example.upload;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface DetailedSalesInvoicesUploadRepository extends MongoRepository<DetailedSalesInvoicesUpload, String> {
    DetailedSalesInvoicesUpload findTopByOrderByUploadedAtDesc();
}

