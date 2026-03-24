package org.example.auth;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;

public interface AuthSessionRepository extends MongoRepository<AuthSessionDocument, String> {

    List<AuthSessionDocument> findByExpiresAtAfter(Instant instant);

    long deleteByUserId(String userId);
}
