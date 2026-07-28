package org.example.ai;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface AiAgentAuditRepository extends MongoRepository<AiAgentAuditLog, String> {
}
