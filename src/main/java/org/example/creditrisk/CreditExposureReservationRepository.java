package org.example.creditrisk;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface CreditExposureReservationRepository extends MongoRepository<CreditExposureReservationDocument, String> {
    Optional<CreditExposureReservationDocument> findByCustomerKey(String customerKey);
}
