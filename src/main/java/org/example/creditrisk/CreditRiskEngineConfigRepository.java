package org.example.creditrisk;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface CreditRiskEngineConfigRepository extends MongoRepository<CreditRiskEngineConfigDocument, String> {
}
