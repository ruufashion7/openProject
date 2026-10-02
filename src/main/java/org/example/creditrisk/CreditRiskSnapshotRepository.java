package org.example.creditrisk;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface CreditRiskSnapshotRepository extends MongoRepository<CreditRiskSnapshotDocument, String> {
    Optional<CreditRiskSnapshotDocument> findByCustomerKey(String customerKey);
}
