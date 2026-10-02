package org.example.creditrisk;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface CreditPaymentEventUploadRepository extends MongoRepository<CreditPaymentEventUpload, String> {
    CreditPaymentEventUpload findTopByOrderByUploadedAtDesc();
}
