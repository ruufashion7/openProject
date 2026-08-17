package org.example.ratelist;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class RateListEntryIdsTest {

    @Test
    void filterById_putsStringBeforeObjectIdSoStringStoredIdsMatch() {
        String hex = "699343e88306d2709ccc19f0";
        Document doc = RateListEntryIds.filterById(hex);

        @SuppressWarnings("unchecked")
        List<Document> or = (List<Document>) doc.get("$or");
        assertEquals(2, or.size());
        assertEquals(hex, or.get(0).get("_id"));
        assertInstanceOf(String.class, or.get(0).get("_id"));
        assertEquals(new ObjectId(hex), or.get(1).get("_id"));
    }

    @Test
    void filterById_usesPlainStringWhenNotObjectIdHex() {
        Document doc = RateListEntryIds.filterById("custom-id");
        assertEquals("custom-id", doc.get("_id"));
        assertFalse(doc.containsKey("$or"));
    }

    @Test
    void filterByIds_combinesStringAndObjectIdBranches() {
        String hex = "699343e88306d2709ccc19f0";
        Document doc = RateListEntryIds.filterByIds(List.of(hex, "custom-id"));

        @SuppressWarnings("unchecked")
        List<Document> or = (List<Document>) doc.get("$or");
        assertEquals(3, or.size());
        assertEquals(hex, or.get(0).get("_id"));
        assertEquals(new ObjectId(hex), or.get(1).get("_id"));
        assertEquals("custom-id", or.get(2).get("_id"));
    }
}
