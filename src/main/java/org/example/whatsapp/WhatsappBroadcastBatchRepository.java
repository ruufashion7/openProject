package org.example.whatsapp;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface WhatsappBroadcastBatchRepository extends MongoRepository<WhatsappBroadcastBatch, String> {

    List<WhatsappBroadcastBatch> findByCreatedByUserIdOrderByCreatedAtDesc(String createdByUserId);

    WhatsappBroadcastBatch findFirstByOrderByCreatedAtDesc();
}
