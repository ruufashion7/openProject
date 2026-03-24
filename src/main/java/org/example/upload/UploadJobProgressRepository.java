package org.example.upload;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface UploadJobProgressRepository extends MongoRepository<UploadJobProgressDocument, String> {
}
