package org.example.creditrisk;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface CreditPaymentPromiseRepository extends MongoRepository<CreditPaymentPromiseDocument, String> {
    List<CreditPaymentPromiseDocument> findByCustomerKeyOrderByPromiseDateDesc(String customerKey);

    long countByCustomerKeyAndBrokenTrue(String customerKey);
}
