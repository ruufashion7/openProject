package org.example.payment;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomerNotesTest {

    @Test
    void appendCapped_dropsOldestWhenOverMax() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        List<CustomerNote> existing = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            existing.add(new CustomerNote("id-" + i, "note " + i, "staff", start.plusSeconds(i), start.plusSeconds(i), "staff"));
        }
        CustomerNote newest = new CustomerNote("id-new", "newest", "Google Drive", start.plusSeconds(10), start.plusSeconds(10), "Google Drive");
        List<CustomerNote> capped = CustomerNotes.appendCapped(existing, newest);
        assertEquals(6, capped.size());
        assertTrue(capped.stream().anyMatch(note -> "newest".equals(note.note())));
        assertFalse(capped.stream().anyMatch(note -> "note 0".equals(note.note())));
        assertEquals("newest", CustomerNotes.latestText(capped));
    }

    @Test
    void containsSameText_trimsAndMatchesAnyExisting() {
        List<CustomerNote> notes = List.of(
                new CustomerNote("a", "Call Monday", "staff", Instant.now(), Instant.now(), "staff")
        );
        assertTrue(CustomerNotes.containsSameText(notes, "  Call Monday  "));
        assertFalse(CustomerNotes.containsSameText(notes, "Call Tuesday"));
    }
}
