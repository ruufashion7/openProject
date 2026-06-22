package org.example.whatsapp;

import org.bson.types.ObjectId;
import org.example.auth.SessionInfo;
import org.example.customer.CustomerPhoneNumbers;
import org.example.whatsapp.dto.BroadcastBatchResponse;
import org.example.whatsapp.dto.BroadcastBatchSummaryResponse;
import org.example.whatsapp.dto.BroadcastRecipientInput;
import org.example.whatsapp.dto.BroadcastRecipientResponse;
import org.example.whatsapp.dto.CreateBroadcastRequest;
import org.example.whatsapp.dto.UpdateRecipientRequest;
import org.example.whatsapp.dto.WaLinkResponse;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class WhatsappBroadcastService {

    public static final int MAX_RECIPIENTS_PER_BATCH = 500;
    public static final int MAX_TEMPLATE_LENGTH = 4000;

    private static final Pattern PLACEHOLDER_KEY_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]{0,63}$");
    private static final Pattern PLACEHOLDER_TOKEN_PATTERN = Pattern.compile("\\{\\{([a-zA-Z][a-zA-Z0-9_]*)}}");
    private static final int MAX_PLACEHOLDER_ENTRIES = 48;
    private static final int MAX_PLACEHOLDER_VALUE_LENGTH = 2000;

    private final WhatsappBroadcastBatchRepository batchRepository;
    private final WhatsappBroadcastRecipientRepository recipientRepository;
    private final WaMeLinkDispatchExecutor waMeLinkDispatchExecutor;

    public WhatsappBroadcastService(
            WhatsappBroadcastBatchRepository batchRepository,
            WhatsappBroadcastRecipientRepository recipientRepository,
            WaMeLinkDispatchExecutor waMeLinkDispatchExecutor) {
        this.batchRepository = batchRepository;
        this.recipientRepository = recipientRepository;
        this.waMeLinkDispatchExecutor = waMeLinkDispatchExecutor;
    }

    public static String normalizeCustomerKey(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return "";
        }
        return displayName.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
    }

    public static String normalizePhoneDigits(String raw) {
        return CustomerPhoneNumbers.normalizeDigitsKey(raw);
    }

    public BroadcastBatchResponse create(SessionInfo session, CreateBroadcastRequest request) {
        if (request == null || request.messageTemplate() == null || request.messageTemplate().isBlank()) {
            throw new IllegalArgumentException("Message template is required");
        }
        if (request.messageTemplate().length() > MAX_TEMPLATE_LENGTH) {
            throw new IllegalArgumentException("Message template is too long");
        }
        if (request.recipients() == null || request.recipients().isEmpty()) {
            throw new IllegalArgumentException("At least one recipient is required");
        }
        if (request.recipients().size() > MAX_RECIPIENTS_PER_BATCH) {
            throw new IllegalArgumentException("Too many recipients (max " + MAX_RECIPIENTS_PER_BATCH + ")");
        }

        Instant now = Instant.now();
        WhatsappBroadcastBatch batch = new WhatsappBroadcastBatch();
        batch.setId(new ObjectId().toHexString());
        batch.setCreatedByUserId(session.userId());
        batch.setMessageTemplate(request.messageTemplate().trim());
        batch.setChannelMode(WhatsAppChannel.WAME_LINK.name());
        batch.setCreatedAt(now);
        batch.setUpdatedAt(now);
        batchRepository.save(batch);

        List<WhatsappBroadcastRecipient> saved = new ArrayList<>();
        for (BroadcastRecipientInput in : request.recipients()) {
            if (in == null) {
                continue;
            }
            String display = in.displayName() != null ? in.displayName().trim() : "";
            if (display.isEmpty()) {
                throw new IllegalArgumentException("Each recipient needs a display name");
            }
            String phoneDigits = normalizePhoneDigits(in.phoneNumber());
            if (phoneDigits == null) {
                throw new IllegalArgumentException("Invalid phone for customer: " + display);
            }
            String key = in.customerKey() != null && !in.customerKey().isBlank()
                    ? in.customerKey().trim()
                    : normalizeCustomerKey(display);
            String rendered = renderTemplate(request.messageTemplate(), display, phoneDigits, in.placeholders());

            WhatsappBroadcastRecipient r = new WhatsappBroadcastRecipient();
            r.setId(new ObjectId().toHexString());
            r.setBatchId(batch.getId());
            r.setCustomerKey(key);
            r.setDisplayName(display);
            r.setPhoneDigits(phoneDigits);
            r.setRenderedMessage(rendered);
            r.setStatus(WhatsappRecipientStatus.NOT_SENT);
            r.setUpdatedAt(now);
            r.setUpdatedByUserId(session.userId());
            saved.add(recipientRepository.save(r));
        }

        if (saved.isEmpty()) {
            batchRepository.deleteById(batch.getId());
            throw new IllegalArgumentException("No valid recipients");
        }

        return toResponse(batch, saved);
    }

    public List<BroadcastBatchSummaryResponse> listSummaries(SessionInfo session) {
        List<WhatsappBroadcastBatch> batches =
                batchRepository.findByCreatedByUserIdOrderByCreatedAtDesc(session.userId());
        if (batches.isEmpty()) {
            return List.of();
        }
        List<String> ids = batches.stream().map(WhatsappBroadcastBatch::getId).toList();
        List<WhatsappBroadcastRecipient> allRecipients = recipientRepository.findByBatchIdIn(ids);
        Map<String, Map<WhatsappRecipientStatus, Long>> countsByBatch = new HashMap<>();
        for (WhatsappBroadcastRecipient r : allRecipients) {
            String bid = r.getBatchId();
            WhatsappRecipientStatus st = r.getStatus() != null ? r.getStatus() : WhatsappRecipientStatus.NOT_SENT;
            countsByBatch
                    .computeIfAbsent(bid, k -> new EnumMap<>(WhatsappRecipientStatus.class))
                    .merge(st, 1L, Long::sum);
        }
        List<BroadcastBatchSummaryResponse> out = new ArrayList<>();
        for (WhatsappBroadcastBatch b : batches) {
            Map<WhatsappRecipientStatus, Long> m = countsByBatch.getOrDefault(b.getId(), Map.of());
            long notSent = m.getOrDefault(WhatsappRecipientStatus.NOT_SENT, 0L);
            long inProg = m.getOrDefault(WhatsappRecipientStatus.IN_PROGRESS, 0L);
            long sent = m.getOrDefault(WhatsappRecipientStatus.SENT, 0L);
            long failed = m.getOrDefault(WhatsappRecipientStatus.FAILED, 0L);
            long total = notSent + inProg + sent + failed;
            out.add(
                    new BroadcastBatchSummaryResponse(
                            b.getId(),
                            b.getCreatedAt() != null ? b.getCreatedAt().toString() : null,
                            b.getChannelMode(),
                            previewMessage(b.getMessageTemplate()),
                            total,
                            notSent,
                            inProg,
                            sent,
                            failed));
        }
        return out;
    }

    public BroadcastBatchResponse get(SessionInfo session, String batchId) {
        WhatsappBroadcastBatch batch = requireOwnedBatch(session, batchId);
        List<WhatsappBroadcastRecipient> list = recipientRepository.findByBatchIdOrderByDisplayNameAsc(batchId);
        return toResponse(batch, list);
    }

    public WaLinkResponse getWaLink(SessionInfo session, String batchId, String recipientId, boolean markOpened) {
        requireOwnedBatch(session, batchId);
        WhatsappBroadcastRecipient r = recipientRepository.findById(recipientId)
                .orElseThrow(() -> new IllegalArgumentException("Recipient not found"));
        if (!batchId.equals(r.getBatchId())) {
            throw new IllegalArgumentException("Recipient does not belong to batch");
        }
        String url = waMeLinkDispatchExecutor.buildWaMeUrl(r.getPhoneDigits(), r.getRenderedMessage());
        if (markOpened && r.getStatus() == WhatsappRecipientStatus.NOT_SENT) {
            r.setStatus(WhatsappRecipientStatus.IN_PROGRESS);
            r.setOpenedAt(Instant.now());
            r.setUpdatedAt(Instant.now());
            r.setUpdatedByUserId(session.userId());
            recipientRepository.save(r);
        }
        return new WaLinkResponse(url);
    }

    public BroadcastRecipientResponse updateRecipient(SessionInfo session, String batchId, String recipientId, UpdateRecipientRequest req) {
        if (req == null || req.status() == null || req.status().isBlank()) {
            throw new IllegalArgumentException("Status is required");
        }
        requireOwnedBatch(session, batchId);
        WhatsappBroadcastRecipient r = recipientRepository.findById(recipientId)
                .orElseThrow(() -> new IllegalArgumentException("Recipient not found"));
        if (!batchId.equals(r.getBatchId())) {
            throw new IllegalArgumentException("Recipient does not belong to batch");
        }
        WhatsappRecipientStatus next;
        try {
            next = WhatsappRecipientStatus.valueOf(req.status().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid status");
        }
        r.setStatus(next);
        r.setFailureReason(req.failureReason() != null && req.failureReason().length() > 500
                ? req.failureReason().substring(0, 500)
                : req.failureReason());
        r.setUpdatedAt(Instant.now());
        r.setUpdatedByUserId(session.userId());
        if (next == WhatsappRecipientStatus.SENT) {
            r.setSentAt(Instant.now());
        }
        if (next == WhatsappRecipientStatus.NOT_SENT) {
            r.setSentAt(null);
            r.setOpenedAt(null);
        }
        recipientRepository.save(r);
        return toRecipientResponse(r);
    }

    private WhatsappBroadcastBatch requireOwnedBatch(SessionInfo session, String batchId) {
        WhatsappBroadcastBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new IllegalArgumentException("Batch not found"));
        if (batch.getCreatedByUserId() == null || !batch.getCreatedByUserId().equals(session.userId())) {
            throw new IllegalArgumentException("Batch not found");
        }
        return batch;
    }

    private static String previewMessage(String template) {
        if (template == null || template.isBlank()) {
            return "";
        }
        String t = template.trim();
        int max = 120;
        if (t.length() <= max) {
            return t;
        }
        return t.substring(0, max) + "…";
    }

    private static Map<String, String> sanitizeIncomingPlaceholders(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        int n = 0;
        for (Map.Entry<String, String> e : raw.entrySet()) {
            if (n >= MAX_PLACEHOLDER_ENTRIES) {
                break;
            }
            String k = e.getKey();
            if (k == null || k.isBlank()) {
                continue;
            }
            k = k.trim();
            if (!PLACEHOLDER_KEY_PATTERN.matcher(k).matches()) {
                continue;
            }
            String v = e.getValue() != null ? e.getValue() : "";
            if (v.length() > MAX_PLACEHOLDER_VALUE_LENGTH) {
                v = v.substring(0, MAX_PLACEHOLDER_VALUE_LENGTH);
            }
            out.put(k, v);
            n++;
        }
        return out;
    }

    /**
     * Replaces {@code {{token}}} with values. Built-ins {@code customerName} and {@code phone} always apply first;
     * per-recipient {@code placeholders} from the client can add or override (e.g. outstanding-due fields).
     */
    private static String renderTemplate(
            String template, String displayName, String phoneDigits, Map<String, String> incomingPlaceholders) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("customerName", displayName != null ? displayName : "");
        vars.put("phone", phoneDigits != null ? phoneDigits : "");
        vars.putAll(sanitizeIncomingPlaceholders(incomingPlaceholders));
        Matcher m = PLACEHOLDER_TOKEN_PATTERN.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            String value = vars.getOrDefault(key, "");
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private BroadcastBatchResponse toResponse(WhatsappBroadcastBatch batch, List<WhatsappBroadcastRecipient> list) {
        List<BroadcastRecipientResponse> rows = list.stream().map(this::toRecipientResponse).toList();
        return new BroadcastBatchResponse(
                batch.getId(),
                batch.getMessageTemplate(),
                batch.getChannelMode(),
                batch.getCreatedAt() != null ? batch.getCreatedAt().toString() : null,
                rows
        );
    }

    private BroadcastRecipientResponse toRecipientResponse(WhatsappBroadcastRecipient r) {
        return new BroadcastRecipientResponse(
                r.getId(),
                r.getCustomerKey(),
                r.getDisplayName(),
                r.getPhoneDigits(),
                r.getRenderedMessage(),
                r.getStatus() != null ? r.getStatus().name() : null,
                r.getFailureReason(),
                r.getOpenedAt() != null ? r.getOpenedAt().toString() : null,
                r.getSentAt() != null ? r.getSentAt().toString() : null
        );
    }
}
