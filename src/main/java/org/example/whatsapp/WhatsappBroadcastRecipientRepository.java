package org.example.whatsapp;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;

public interface WhatsappBroadcastRecipientRepository extends MongoRepository<WhatsappBroadcastRecipient, String> {
    List<WhatsappBroadcastRecipient> findByBatchIdOrderByDisplayNameAsc(String batchId);

    List<WhatsappBroadcastRecipient> findByBatchIdIn(Collection<String> batchIds);
}
