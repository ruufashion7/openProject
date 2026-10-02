package org.example.creditrisk;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface CreditDecisionAuditRepository extends MongoRepository<CreditDecisionAuditDocument, String> {
    List<CreditDecisionAuditDocument> findByCustomerKeyOrderByDecidedAtDesc(String customerKey);
}
