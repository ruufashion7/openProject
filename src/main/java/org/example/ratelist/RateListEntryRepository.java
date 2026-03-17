package org.example.ratelist;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface RateListEntryRepository extends MongoRepository<RateListEntry, String> {
    List<RateListEntry> findAllByOrderByCreatedAtDesc();
    
    // Check for duplicate entry: same date, type, productName, and size
    // Returns list to handle cases where duplicates already exist in database
    List<RateListEntry> findByDateAndTypeAndProductNameAndSize(
            String date, String type, String productName, String size
    );
}

