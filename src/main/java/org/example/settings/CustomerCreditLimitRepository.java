package org.example.settings;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface CustomerCreditLimitRepository extends MongoRepository<CustomerCreditLimitDocument, String> {
}
