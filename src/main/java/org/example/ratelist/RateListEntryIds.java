package org.example.ratelist;

import org.bson.Document;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Builds a raw BSON {@code _id} filter for {@code rate_list}.
 * <p>
 * Entries are saved with {@code @Id String}, so Mongo stores {@code _id} as a hex <em>string</em>
 * that looks like an ObjectId. Spring Data's {@code QueryMapper} still converts 24-char hex values
 * to {@link ObjectId} when the query is mapped through {@code RateListEntry.class}, so
 * {@code findById}/{@code deleteById} and {@code mongoTemplate.remove(query, RateListEntry.class)}
 * miss the row (HTTP 404) even though GET returns it.
 * Use this document with {@code MongoCollection} so the driver sends the filter unchanged.
 */
public final class RateListEntryIds {
    private RateListEntryIds() {}

    public static Document filterById(String id) {
        if (id != null && ObjectId.isValid(id)) {
            List<Document> or = new ArrayList<>(2);
            or.add(new Document("_id", id));
            or.add(new Document("_id", new ObjectId(id)));
            return new Document("$or", or);
        }
        return new Document("_id", id);
    }

    public static Document filterByIds(Collection<String> ids) {
        List<Document> or = new ArrayList<>();
        if (ids != null) {
            for (String id : ids) {
                if (id == null || id.isBlank()) {
                    continue;
                }
                Document filter = filterById(id);
                Object nested = filter.get("$or");
                if (nested instanceof List<?> nestedList) {
                    for (Object part : nestedList) {
                        if (part instanceof Document doc) {
                            or.add(doc);
                        }
                    }
                } else {
                    or.add(filter);
                }
            }
        }
        if (or.isEmpty()) {
            return new Document("_id", "__none__");
        }
        if (or.size() == 1) {
            return or.get(0);
        }
        return new Document("$or", or);
    }
}
