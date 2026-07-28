package org.example.ai;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface AiAgentExportRepository extends MongoRepository<AiAgentExport, String> {

    Optional<AiAgentExport> findByIdAndUserId(String id, String userId);
}
