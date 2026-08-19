package org.example.drive;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface DrivePaymentDateSyncStateRepository extends MongoRepository<DrivePaymentDateSyncState, String> {
}
