package org.example.creditrisk;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface CreditLimitAuditRepository extends MongoRepository<CreditLimitAuditDocument, String> {
    List<CreditLimitAuditDocument> findByCustomerKeyOrderByChangedAtDesc(String customerKey);
}
