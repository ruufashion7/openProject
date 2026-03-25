package org.example.payment;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Merges duplicate {@code customer_master} rows that share the same {@code customerKey}
 * (e.g. one with {@code _id} as String and another as ObjectId with the same hex).
 * Then ensures a unique index on {@code customerKey}.
 */
@Service
public class CustomerMasterDedupeService {

    private static final Logger logger = LoggerFactory.getLogger(CustomerMasterDedupeService.class);

    public static final String COLLECTION = "customer_master";

    private final MongoTemplate mongoTemplate;

    public CustomerMasterDedupeService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public DedupeResult dedupeAndEnsureUniqueIndex() {
        List<Document> all = mongoTemplate.findAll(Document.class, COLLECTION);
        Map<String, List<Document>> byKey = all.stream()
                .filter(d -> d.getString("customerKey") != null && !d.getString("customerKey").isBlank())
                .collect(Collectors.groupingBy(d -> d.getString("customerKey")));

        int duplicateGroups = 0;
        int documentsRemoved = 0;

        for (Map.Entry<String, List<Document>> e : byKey.entrySet()) {
            List<Document> group = e.getValue();
            if (group.size() <= 1) {
                continue;
            }
            duplicateGroups++;
            Document keeperDoc = selectKeeperDocument(group);
            List<Document> losers = new ArrayList<>(group);
            losers.removeIf(d -> sameIdentity(d, keeperDoc));

            PaymentDateOverride keeper = mongoTemplate.getConverter().read(PaymentDateOverride.class, keeperDoc);
            for (Document loserDoc : losers) {
                PaymentDateOverride loser = mongoTemplate.getConverter().read(PaymentDateOverride.class, loserDoc);
                keeper = merge(keeper, loser);
            }

            mongoTemplate.save(keeper, COLLECTION);

            for (Document loserDoc : losers) {
                Object rawId = loserDoc.get("_id");
                Query q = Query.query(Criteria.where("_id").is(rawId));
                mongoTemplate.remove(q, COLLECTION);
                documentsRemoved++;
                logger.info("Removed duplicate customer_master _id={} (type={}) for customerKey={}",
                        rawId, rawId != null ? rawId.getClass().getSimpleName() : "null", e.getKey());
            }
        }

        ensureUniqueCustomerKeyIndex();

        return new DedupeResult(duplicateGroups, documentsRemoved, byKey.size());
    }

    private boolean sameIdentity(Document a, Document b) {
        return Objects.equals(a.get("_id"), b.get("_id"));
    }

    /**
     * Prefer a document whose {@code _id} is {@link ObjectId} (Spring default); if multiple, keep newest {@code updatedAt}.
     * If none are ObjectId, keep the document with the newest {@code updatedAt}.
     */
    Document selectKeeperDocument(List<Document> group) {
        List<Document> objectIdRows = group.stream()
                .filter(d -> d.get("_id") instanceof ObjectId)
                .toList();
        if (!objectIdRows.isEmpty()) {
            return objectIdRows.stream()
                    .max((a, b) -> compareUpdatedAt(parseUpdatedAt(a), parseUpdatedAt(b)))
                    .orElse(objectIdRows.getFirst());
        }
        return group.stream()
                .max((a, b) -> compareUpdatedAt(parseUpdatedAt(a), parseUpdatedAt(b)))
                .orElse(group.getFirst());
    }

    private static int compareUpdatedAt(Instant a, Instant b) {
        return a.compareTo(b);
    }

    private Instant parseUpdatedAt(Document d) {
        Object v = d.get("updatedAt");
        if (v == null) {
            return Instant.EPOCH;
        }
        if (v instanceof Date) {
            return ((Date) v).toInstant();
        }
        if (v instanceof Instant) {
            return (Instant) v;
        }
        if (v instanceof String) {
            try {
                return Instant.parse((String) v);
            } catch (Exception ignored) {
                return Instant.EPOCH;
            }
        }
        return Instant.EPOCH;
    }

    private PaymentDateOverride merge(PaymentDateOverride keeper, PaymentDateOverride loser) {
        LinkedHashMap<String, CustomerNote> notesById = new LinkedHashMap<>();
        for (CustomerNote n : keeper.notes()) {
            notesById.put(n.id(), n);
        }
        for (CustomerNote n : loser.notes()) {
            notesById.putIfAbsent(n.id(), n);
        }
        List<CustomerNote> mergedNotes = new ArrayList<>(notesById.values());

        Instant keeperT = keeper.updatedAt() != null ? keeper.updatedAt() : Instant.EPOCH;
        Instant loserT = loser.updatedAt() != null ? loser.updatedAt() : Instant.EPOCH;
        boolean preferLoser = loserT.isAfter(keeperT);

        String nextPaymentDate = firstNonBlank(
                preferLoser ? loser.nextPaymentDate() : keeper.nextPaymentDate(),
                preferLoser ? keeper.nextPaymentDate() : loser.nextPaymentDate());
        String whatsApp = firstNonBlank(
                preferLoser ? loser.whatsAppStatus() : keeper.whatsAppStatus(),
                preferLoser ? keeper.whatsAppStatus() : loser.whatsAppStatus());
        String category = firstNonBlank(
                preferLoser ? loser.customerCategory() : keeper.customerCategory(),
                preferLoser ? keeper.customerCategory() : loser.customerCategory());
        String phone = firstNonBlank(
                preferLoser ? loser.phoneNumber() : keeper.phoneNumber(),
                preferLoser ? keeper.phoneNumber() : loser.phoneNumber());
        String address = firstNonBlank(
                preferLoser ? loser.address() : keeper.address(),
                preferLoser ? keeper.address() : loser.address());
        String place = firstNonBlank(
                preferLoser ? loser.place() : keeper.place(),
                preferLoser ? keeper.place() : loser.place());
        Double lat = preferLoser ? loser.latitude() : keeper.latitude();
        if (lat == null) {
            lat = preferLoser ? keeper.latitude() : loser.latitude();
        }
        Double lon = preferLoser ? loser.longitude() : keeper.longitude();
        if (lon == null) {
            lon = preferLoser ? keeper.longitude() : loser.longitude();
        }
        boolean needsFollowUp = preferLoser
                ? Boolean.TRUE.equals(loser.needsFollowUp())
                : Boolean.TRUE.equals(keeper.needsFollowUp());
        if (!preferLoser && Boolean.TRUE.equals(loser.needsFollowUp())) {
            needsFollowUp = true;
        }
        boolean active = preferLoser
                ? loser.isActive()
                : keeper.isActive();
        if (!preferLoser && loser.isActive()) {
            active = true;
        }

        Instant updatedAt = keeperT.isAfter(loserT) ? keeperT : loserT;

        return new PaymentDateOverride(
                keeper.id(),
                keeper.customerKey(),
                firstNonBlank(keeper.customerName(), loser.customerName()),
                nextPaymentDate != null ? nextPaymentDate : "",
                phone,
                whatsApp,
                category,
                active,
                needsFollowUp,
                address,
                place,
                lat,
                lon,
                mergedNotes,
                updatedAt
        );
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return a != null ? a : b;
    }

    public void ensureUniqueCustomerKeyIndex() {
        Index index = new Index()
                .on("customerKey", Sort.Direction.ASC)
                .unique()
                .named("customerKey_unique");
        mongoTemplate.indexOps(COLLECTION).ensureIndex(index);
        logger.info("Ensured unique index customerKey_unique on {}", COLLECTION);
    }

    public record DedupeResult(
            int duplicateGroupsMerged,
            int documentsRemoved,
            int distinctCustomerKeys
    ) {}
}
