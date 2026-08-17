package org.example.bill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.ai.AiAgentLlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class BillExtractService {

    private static final Logger logger = LoggerFactory.getLogger(BillExtractService.class);
    static final int MAX_FILES = 50;
    static final long MAX_BYTES = 25L * 1024 * 1024;
    static final int MAX_GAP_FILL = 80;

    private static final Set<String> ALLOWED_TYPES = Set.of(
            "image/jpeg",
            "image/jpg",
            "image/png",
            "image/webp",
            "image/gif"
    );

    private static final Pattern BILL_NO = Pattern.compile("(\\d+)");

    private static final String EXTRACT_PROMPT = """
            You read a photo of a handwritten retail cash-memo / bill.

            Return a JSON object with exactly these string fields:
            billNo, totalAmount, discount, amountAfterDiscount, payment, salesman, time, remark

            Rules:
            - Copy values as written. If a field is not on the bill, use "".
            - Never invent numbers, names, or times.
            - billNo: the bill / invoice number only (digits), not the date.
            - totalAmount: gross / item sum BEFORE discount (figure above Dis / Less, or qty×rate total).
            - discount: Dis / Less amount only. "" if none.
            - amountAfterDiscount: final Total / net payable. If no discount, same as totalAmount.
            - payment: Cash / Card / GPay / UPI as written, including split amounts
              (example: "Cash 300 / GPay 200" or "GPay" or "Cash").
            - salesman: circled initial or name on the bill (often a circled letter). Signature squiggle is not a name — use "".
            - time: handwritten clock time if present (example 6:55). Do not use the printed exchange hours.
            - remark: only if useful (split payment, unreadable field). Do not copy printed policy
              (exchange within 7 days, no cash refund).
            """;

    private final AiAgentLlmClient llmClient;
    private final ObjectMapper objectMapper;

    public BillExtractService(AiAgentLlmClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    public BillExtractStatus status() {
        boolean configured = llmClient.isConfigured();
        return new BillExtractStatus(
                true,
                configured,
                configured,
                configured ? llmClient.model() : null,
                configured
                        ? null
                        : "Configure AI_AGENT_API_KEY on the server (same key as AI Data Agent). Use a vision-capable model."
        );
    }

    public BillExtractResponse extract(List<MultipartFile> files) {
        if (!llmClient.isConfigured()) {
            throw new IllegalStateException(
                    "Bill reader requires an LLM. Set AI_AGENT_API_KEY (and optional AI_AGENT_BASE_URL / AI_AGENT_MODEL).");
        }
        List<MultipartFile> images = validate(files);
        List<BillExtractRow> extracted = readAll(images);
        List<BillExtractRow> rows = fillMissingBills(extracted);
        return new BillExtractResponse(true, llmClient.model(), images.size(), rows);
    }

    private List<MultipartFile> validate(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("Upload at least one bill photo.");
        }
        List<MultipartFile> images = files.stream()
                .filter(f -> f != null && !f.isEmpty())
                .toList();
        if (images.isEmpty()) {
            throw new IllegalArgumentException("Upload at least one bill photo.");
        }
        if (images.size() > MAX_FILES) {
            throw new IllegalArgumentException("Maximum " + MAX_FILES + " photos per run.");
        }
        for (MultipartFile file : images) {
            if (file.getSize() > MAX_BYTES) {
                throw new IllegalArgumentException(
                        (file.getOriginalFilename() == null ? "A file" : file.getOriginalFilename())
                                + " is larger than " + (MAX_BYTES / (1024 * 1024)) + " MB.");
            }
            if (!isAllowedImage(file)) {
                throw new IllegalArgumentException(
                        "Only JPEG, PNG, WebP, or GIF photos are allowed"
                                + (file.getOriginalFilename() == null ? "." : ": " + file.getOriginalFilename()));
            }
        }
        return images;
    }

    private boolean isAllowedImage(MultipartFile file) {
        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT).trim();
        if (ALLOWED_TYPES.contains(type)) {
            return true;
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")
                || name.endsWith(".webp") || name.endsWith(".gif");
    }

    private List<BillExtractRow> readAll(List<MultipartFile> images) {
        List<BillExtractRow> rows = new ArrayList<>(images.size());
        try (ExecutorService pool = Executors.newFixedThreadPool(Math.min(3, images.size()))) {
            List<CompletableFuture<BillExtractRow>> futures = images.stream()
                    .map(file -> CompletableFuture.supplyAsync(() -> readOne(file), pool))
                    .toList();
            for (CompletableFuture<BillExtractRow> future : futures) {
                rows.add(future.join());
            }
        }
        return rows;
    }

    private BillExtractRow readOne(MultipartFile file) {
        try {
            String mime = normalizeMime(file);
            String dataUrl = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(file.getBytes());
            ArrayNode messages = objectMapper.createArrayNode();
            ObjectNode user = messages.addObject();
            user.put("role", "user");
            ArrayNode content = user.putArray("content");
            ObjectNode text = content.addObject();
            text.put("type", "text");
            text.put("text", EXTRACT_PROMPT);
            ObjectNode image = content.addObject();
            image.put("type", "image_url");
            ObjectNode imageUrl = image.putObject("image_url");
            imageUrl.put("url", dataUrl);

            JsonNode response = llmClient.chatJson(messages);
            String raw = response.path("choices").path(0).path("message").path("content").asText("");
            return parseRow(raw);
        } catch (Exception e) {
            logger.warn("Bill extract failed for {}: {}", file.getOriginalFilename(), e.getMessage());
            return new BillExtractRow(
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "Could not read this photo",
                    false
            );
        }
    }

    private static String normalizeMime(MultipartFile file) {
        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT).trim();
        if (ALLOWED_TYPES.contains(type)) {
            return "image/jpg".equals(type) ? "image/jpeg" : type;
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        if (name.endsWith(".gif")) {
            return "image/gif";
        }
        return "image/jpeg";
    }

    BillExtractRow parseRow(String raw) {
        JsonNode node = parseJsonObject(raw);
        if (node == null || !node.isObject()) {
            return new BillExtractRow("", "", "", "", "", "", "", "Could not read this photo", false);
        }
        return new BillExtractRow(
                text(node, "billNo"),
                text(node, "totalAmount"),
                text(node, "discount"),
                text(node, "amountAfterDiscount"),
                text(node, "payment"),
                text(node, "salesman"),
                text(node, "time"),
                text(node, "remark"),
                false
        ).withDefaults();
    }

    private JsonNode parseJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf('\n');
            int end = trimmed.lastIndexOf("```");
            if (start > 0 && end > start) {
                trimmed = trimmed.substring(start + 1, end).trim();
            }
        }
        try {
            return objectMapper.readTree(trimmed);
        } catch (Exception e) {
            logger.debug("Bill JSON parse failed: {}", e.getMessage());
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return "";
        }
        if (value.isNumber()) {
            String s = value.asText();
            if (s.endsWith(".0")) {
                return s.substring(0, s.length() - 2);
            }
            return s;
        }
        return value.asText("").trim();
    }

    /**
     * Between the lowest and highest numeric bill numbers, insert blank rows for gaps.
     * Unreadable photos (no bill no) stay at the end.
     */
    static List<BillExtractRow> fillMissingBills(List<BillExtractRow> extracted) {
        if (extracted == null || extracted.isEmpty()) {
            return List.of();
        }
        Map<Integer, BillExtractRow> byNo = new LinkedHashMap<>();
        List<BillExtractRow> unnumbered = new ArrayList<>();
        for (BillExtractRow row : extracted) {
            Integer n = parseBillNo(row.billNo());
            if (n == null) {
                unnumbered.add(row);
            } else {
                byNo.putIfAbsent(n, row);
            }
        }
        if (byNo.isEmpty()) {
            return List.copyOf(extracted);
        }
        int min = byNo.keySet().stream().min(Integer::compareTo).orElseThrow();
        int max = byNo.keySet().stream().max(Integer::compareTo).orElseThrow();
        if (max - min > MAX_GAP_FILL) {
            List<BillExtractRow> sorted = new ArrayList<>(byNo.values());
            sorted.sort(Comparator.comparingInt(r -> parseBillNo(r.billNo())));
            sorted.addAll(unnumbered);
            return sorted;
        }
        List<BillExtractRow> out = new ArrayList<>();
        for (int n = min; n <= max; n++) {
            BillExtractRow row = byNo.get(n);
            out.add(row != null ? row : BillExtractRow.blank(String.valueOf(n), true));
        }
        out.addAll(unnumbered);
        return out;
    }

    static Integer parseBillNo(String billNo) {
        if (billNo == null || billNo.isBlank()) {
            return null;
        }
        Matcher m = BILL_NO.matcher(billNo.trim());
        if (!m.find()) {
            return null;
        }
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
