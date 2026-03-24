package org.example.payment;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.Optional;

public interface PaymentDateOverrideRepository extends MongoRepository<PaymentDateOverride, String> {
    Optional<PaymentDateOverride> findFirstByCustomerKeyOrderByIdAsc(String customerKey);
    void deleteByCustomerKey(String customerKey);
    
    /**
     * Find PaymentDateOverride document containing a note with the specified note ID.
     * Uses MongoDB $elemMatch to efficiently query embedded notes array.
     */
    @Query("{ 'notes.id': ?0 }")
    Optional<PaymentDateOverride> findByNotesId(String noteId);
}

