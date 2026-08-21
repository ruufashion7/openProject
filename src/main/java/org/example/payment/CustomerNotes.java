package org.example.payment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Shared rules for customer notes in the app and Google Drive sync.
 */
public final class CustomerNotes {

    public static final int MAX_NOTES = 6;
    public static final int MAX_NOTE_LENGTH = 5000;
    public static final String DRIVE_AUTHOR = "Google Drive";

    private CustomerNotes() {
    }

    public static String normalizeText(String text) {
        return text == null ? "" : text.trim();
    }

    public static String clip(String text) {
        String normalized = normalizeText(text);
        if (normalized.length() <= MAX_NOTE_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_NOTE_LENGTH);
    }

    public static Optional<CustomerNote> latest(List<CustomerNote> notes) {
        if (notes == null || notes.isEmpty()) {
            return Optional.empty();
        }
        return notes.stream()
                .max(Comparator
                        .comparing(CustomerNote::createdAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(CustomerNote::id, Comparator.nullsLast(Comparator.naturalOrder())));
    }

    public static String latestText(List<CustomerNote> notes) {
        return latest(notes)
                .map(CustomerNote::note)
                .map(CustomerNotes::normalizeText)
                .orElse("");
    }

    public static boolean containsSameText(List<CustomerNote> notes, String text) {
        String needle = normalizeText(text);
        if (needle.isEmpty() || notes == null || notes.isEmpty()) {
            return false;
        }
        return notes.stream().anyMatch(note -> needle.equals(normalizeText(note.note())));
    }

    public static CustomerNote newDriveNote(String text) {
        Instant now = Instant.now();
        return new CustomerNote(
                UUID.randomUUID().toString(),
                clip(text),
                DRIVE_AUTHOR,
                now,
                now,
                DRIVE_AUTHOR
        );
    }

    /**
     * Appends a note and drops the oldest until at most {@link #MAX_NOTES} remain.
     */
    public static List<CustomerNote> appendCapped(List<CustomerNote> existing, CustomerNote newNote) {
        List<CustomerNote> out = new ArrayList<>();
        if (existing != null) {
            out.addAll(existing);
        }
        out.add(newNote);
        while (out.size() > MAX_NOTES) {
            int oldestIdx = 0;
            Instant oldestAt = createdAt(out.get(0));
            for (int i = 1; i < out.size(); i++) {
                Instant at = createdAt(out.get(i));
                if (at.isBefore(oldestAt)) {
                    oldestAt = at;
                    oldestIdx = i;
                }
            }
            out.remove(oldestIdx);
        }
        return out;
    }

    private static Instant createdAt(CustomerNote note) {
        return note.createdAt() == null ? Instant.EPOCH : note.createdAt();
    }
}
