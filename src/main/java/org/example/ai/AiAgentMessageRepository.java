package org.example.ai;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface AiAgentMessageRepository extends MongoRepository<AiAgentMessage, String> {

    List<AiAgentMessage> findByConversationIdAndUserIdOrderByCreatedAtAsc(String conversationId, String userId);

    void deleteByConversationIdAndUserId(String conversationId, String userId);
}
