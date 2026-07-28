package org.example.ai;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface AiAgentConversationRepository extends MongoRepository<AiAgentConversation, String> {

    List<AiAgentConversation> findByUserIdOrderByUpdatedAtDesc(String userId);

    Optional<AiAgentConversation> findByIdAndUserId(String id, String userId);

    void deleteByIdAndUserId(String id, String userId);
}
