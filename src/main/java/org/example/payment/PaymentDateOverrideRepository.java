package org.example.payment;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PaymentDateOverrideRepository extends MongoRepository<PaymentDateOverride, String> {
    Optional<PaymentDateOverride> findFirstByCustomerKeyOrderByIdAsc(String customerKey);

    long countByCustomerKey(String customerKey);

    List<PaymentDateOverride> findAllByCustomerKeyOrderByIdAsc(String customerKey);
    void deleteByCustomerKey(String customerKey);
    
    /**
     * Find PaymentDateOverride document containing a note with the specified note ID.
     * Uses MongoDB $elemMatch to efficiently query embedded notes array.
     */
    @Query("{ 'notes.id': ?0 }")
    Optional<PaymentDateOverride> findByNotesId(String noteId);

    @Query(value = "{ 'phoneNumber': { $exists: true, $nin: [null, ''] } }", count = true)
    long countWithPhoneNumber();

    @Query(value = "{ 'latitude': { $exists: true, $ne: null }, 'longitude': { $exists: true, $ne: null } }", count = true)
    long countWithCoordinates();

    @Query(value = "{ 'active': { $ne: false } }", count = true)
    long countActiveCustomers();
}

