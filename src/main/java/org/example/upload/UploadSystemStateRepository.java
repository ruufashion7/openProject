package org.example.upload;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface UploadSystemStateRepository extends MongoRepository<UploadSystemStateDocument, String> {
}
