package org.example.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.api.AnalyticsController;
import org.example.api.CustomerNotesController;
import org.example.api.CustomerSummaryResponse;
import org.example.api.PaymentDateCustomerCard;
import org.example.api.request.CustomerSearchRequest;
import org.example.auth.SessionInfo;
import org.example.auth.SessionPermissions;
import org.example.payment.PaymentDateOverride;
import org.example.payment.PaymentDateOverrideRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Executes read-only agent tools against existing analytics APIs / Mongo, respecting the caller's permissions.
 */
@Service
public class AiAgentToolExecutor {

    private static final Logger logger = LoggerFactory.getLogger(AiAgentToolExecutor.class);

    private final AnalyticsController analyticsController;
    private final CustomerNotesController customerNotesController;
    private final PaymentDateOverrideRepository paymentDateOverrideRepository;
    private final AiAgentPdfService pdfService;
    private final AiAgentExportRepository exportRepository;
    private final ObjectMapper objectMapper;
    private final long exportTtlMinutes;

    public AiAgentToolExecutor(
            AnalyticsController analyticsController,
            CustomerNotesController customerNotesController,
            PaymentDateOverrideRepository paymentDateOverrideRepository,
            AiAgentPdfService pdfService,
            AiAgentExportRepository exportRepository,
            ObjectMapper objectMapper,
            @Value("${ai.agent.export-ttl-minutes:60}") long exportTtlMinutes
    ) {
        this.analyticsController = analyticsController;
        this.customerNotesController = customerNotesController;
        this.paymentDateOverrideRepository = paymentDateOverrideRepository;
        this.pdfService = pdfService;
        this.exportRepository = exportRepository;
        this.objectMapper = objectMapper;
        this.exportTtlMinutes = exportTtlMinutes;
    }

    public ArrayNode toolDefinitions(SessionInfo session) {
        ArrayNode tools = objectMapper.createArrayNode();
        if (SessionPermissions.canAccessOutstandingPage(session)
                || SessionPermissions.canAccessDetailsOrOutstanding(session)) {
            ObjectNode props = objectMapper.createObjectNode();
            props.set("query", prop("string", "Name or phone fragment"));
            props.set("limit", prop("integer", "Max results (default 20)"));
            tools.add(fn("search_customers",
                    "Search customers by name or phone from customer master.",
                    objectParams(props, "query")));
        }
        if (SessionPermissions.canAccessDetailsOrOutstanding(session)) {
            ObjectNode dueProps = objectMapper.createObjectNode();
            dueProps.set("customer", prop("string", "Customer name"));
            dueProps.set("phone", prop("string", "Phone number"));
            tools.add(fn("get_customer_due",
                    "Get outstanding due summary for one customer by name and/or phone.",
                    objectParams(dueProps)));
            tools.add(fn("get_customer_ledger",
                    "Get invoice/sales ledger lines for one customer.",
                    objectParams(dueProps.deepCopy())));
        }
        if (SessionPermissions.canAccessOutstandingPage(session)) {
            ObjectNode listProps = objectMapper.createObjectNode();
            listProps.set("query", prop("string", "Filter by customer name or phone"));
            listProps.set("min_amount", prop("number", "Minimum total due"));
            listProps.set("max_amount", prop("number", "Maximum total due"));
            listProps.set("category", prop("string", "Customer category A/B/C/semi-wholesale"));
            listProps.set("limit", prop("integer", "Max rows (default 50)"));
            listProps.set("sort_by", prop("string", "amount_desc | amount_asc | name"));
            tools.add(fn("list_outstanding_due",
                    "List outstanding due board customers with optional filters (name/phone query, min/max amount, category, limit, sort).",
                    objectParams(listProps)));
        }
        if (SessionPermissions.canViewCustomerNotes(session)) {
            ObjectNode noteProps = objectMapper.createObjectNode();
            noteProps.set("customer", prop("string", "Customer name"));
            noteProps.set("phone", prop("string", "Phone number"));
            tools.add(fn("get_customer_notes",
                    "List notes for a customer.",
                    objectParams(noteProps)));
        }
        ObjectNode exportProps = objectMapper.createObjectNode();
        exportProps.set("title", prop("string", "PDF title"));
        ObjectNode columnsSchema = objectMapper.createObjectNode();
        columnsSchema.put("type", "array");
        columnsSchema.set("items", objectMapper.createObjectNode().put("type", "string"));
        exportProps.set("columns", columnsSchema);
        ObjectNode rowsSchema = objectMapper.createObjectNode();
        rowsSchema.put("type", "array");
        ObjectNode rowItems = objectMapper.createObjectNode();
        rowItems.put("type", "array");
        rowItems.set("items", objectMapper.createObjectNode().put("type", "string"));
        rowsSchema.set("items", rowItems);
        exportProps.set("rows", rowsSchema);
        tools.add(fn("export_pdf",
                "Generate a downloadable PDF from a title, column headers, and row values (string cells).",
                objectParams(exportProps, "columns", "rows")));
        return tools;
    }

    private ObjectNode objectParams(ObjectNode properties, String... required) {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");
        parameters.set("properties", properties);
        if (required != null && required.length > 0) {
            ArrayNode req = objectMapper.createArrayNode();
            for (String r : required) {
                req.add(r);
            }
            parameters.set("required", req);
        }
        return parameters;
    }

    public ToolResult execute(String name, JsonNode args, SessionInfo session, String authHeader) {
        try {
            return switch (name) {
                case "search_customers" -> searchCustomers(args, session);
                case "get_customer_due" -> getCustomerDue(args, session, authHeader);
                case "get_customer_ledger" -> getCustomerLedger(args, session, authHeader);
                case "list_outstanding_due" -> listOutstandingDue(args, session, authHeader);
                case "get_customer_notes" -> getCustomerNotes(args, session, authHeader);
                case "export_pdf" -> exportPdf(args, session);
                default -> ToolResult.error("Unknown tool: " + name);
            };
        } catch (Exception e) {
            logger.warn("Tool {} failed: {}", name, e.toString());
            return ToolResult.error("Tool failed: " + e.getMessage());
        }
    }

    private ToolResult searchCustomers(JsonNode args, SessionInfo session) {
        if (!(SessionPermissions.canAccessOutstandingPage(session)
                || SessionPermissions.canAccessDetailsOrOutstanding(session))) {
            return ToolResult.error("Not permitted to search customers.");
        }
        String query = text(args, "query");
        if (query.isBlank()) {
            return ToolResult.error("query is required");
        }
        int limit = Math.min(50, Math.max(1, args.path("limit").asInt(20)));
        String q = query.toLowerCase(Locale.ROOT);
        String digits = query.replaceAll("\\D", "");

        List<Map<String, Object>> matches = paymentDateOverrideRepository.findAll().stream()
                .filter(o -> matchesCustomer(o, q, digits))
                .sorted(Comparator.comparing(o -> o.customerName() == null ? "" : o.customerName()))
                .limit(limit)
                .map(o -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("customer", o.customerName());
                    row.put("phone", o.phoneNumber());
                    row.put("category", o.customerCategory());
                    row.put("place", o.place());
                    row.put("nextPaymentDate", o.nextPaymentDate());
                    row.put("active", o.isActive());
                    return row;
                })
                .collect(Collectors.toList());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", matches.size());
        payload.put("customers", matches);
        if (matches.size() > 1) {
            payload.put("hint", "Multiple matches — ask the user which customer if needed.");
        }
        return ToolResult.ok(payload, tableAttachment(
                "Customer search: " + query,
                List.of("Customer", "Phone", "Category", "Place", "Next payment"),
                matches.stream()
                        .map(m -> List.of(
                                str(m.get("customer")),
                                str(m.get("phone")),
                                str(m.get("category")),
                                str(m.get("place")),
                                str(m.get("nextPaymentDate"))
                        ))
                        .toList()
        ));
    }

    private ToolResult getCustomerDue(JsonNode args, SessionInfo session, String authHeader) {
        if (!SessionPermissions.canAccessDetailsOrOutstanding(session)) {
            return ToolResult.error("Not permitted to view customer dues.");
        }
        String customer = text(args, "customer");
        String phone = text(args, "phone");
        if (customer.isBlank() && phone.isBlank()) {
            return ToolResult.error("Provide customer and/or phone");
        }
        ResponseEntity<CustomerSummaryResponse> response = analyticsController.customerSummary(
                authHeader, new CustomerSearchRequest(blankToNull(customer), blankToNull(phone)));
        if (response.getStatusCode() == HttpStatus.FORBIDDEN) {
            return ToolResult.error("Forbidden");
        }
        if (response.getStatusCode().is4xxClientError() || response.getBody() == null) {
            return ToolResult.error("Could not load customer summary");
        }
        CustomerSummaryResponse body = response.getBody();
        Map<String, Object> payload = objectMapper.convertValue(body, Map.class);
        return ToolResult.ok(payload, tableAttachment(
                "Due: " + str(body.customer()),
                List.of("Customer", "Phone", "Total due", "0-45", "46-90", "90+", "Category", "Next payment"),
                List.of(List.of(
                        str(body.customer()),
                        str(body.phoneNumber()),
                        formatInr(body.totalAmount()),
                        formatInr(body.withinAmount()),
                        formatInr(body.midAmount()),
                        formatInr(body.beyondAmount()),
                        str(body.customerCategory()),
                        str(body.nextPaymentDate())
                ))
        ));
    }

    private ToolResult getCustomerLedger(JsonNode args, SessionInfo session, String authHeader) {
        if (!SessionPermissions.canAccessDetailsOrOutstanding(session)) {
            return ToolResult.error("Not permitted to view ledger.");
        }
        String customer = text(args, "customer");
        String phone = text(args, "phone");
        ResponseEntity<?> response = analyticsController.customerLedger(
                authHeader, new CustomerSearchRequest(blankToNull(customer), blankToNull(phone)));
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            return ToolResult.error("Could not load ledger");
        }
        List<?> entries = (List<?>) response.getBody();
        List<List<String>> rows = new ArrayList<>();
        List<Map<String, Object>> maps = new ArrayList<>();
        for (Object entry : entries) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = objectMapper.convertValue(entry, Map.class);
            maps.add(m);
            rows.add(List.of(
                    str(m.get("invoiceDate")),
                    str(m.get("voucherNo")),
                    str(m.get("receivedAmount")),
                    str(m.get("currentDue")),
                    str(m.get("ageingDays"))
            ));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", maps.size());
        // Keep LLM payload small (Groq/OpenAI request size limits).
        int llmRows = Math.min(15, maps.size());
        payload.put("entries", maps.subList(0, llmRows));
        if (maps.size() > llmRows) {
            payload.put("truncated", true);
            payload.put("shown", llmRows);
        }
        return ToolResult.ok(payload, tableAttachment(
                "Ledger: " + (customer.isBlank() ? phone : customer),
                List.of("Date", "Voucher", "Received", "Current due", "Ageing days"),
                rows.size() > 80 ? rows.subList(0, 80) : rows
        ));
    }

    private ToolResult listOutstandingDue(JsonNode args, SessionInfo session, String authHeader) {
        if (!SessionPermissions.canAccessOutstandingPage(session)) {
            return ToolResult.error("Not permitted to view outstanding due.");
        }
        ResponseEntity<List<PaymentDateCustomerCard>> response = analyticsController.listOutstandingDue(authHeader);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            return ToolResult.error("Could not load outstanding due board");
        }
        String query = text(args, "query").toLowerCase(Locale.ROOT);
        String digits = text(args, "query").replaceAll("\\D", "");
        double minAmount = args.path("min_amount").asDouble(Double.NEGATIVE_INFINITY);
        double maxAmount = args.path("max_amount").asDouble(Double.POSITIVE_INFINITY);
        String category = text(args, "category").toLowerCase(Locale.ROOT);
        // Cap hard — large boards blow Groq/OpenAI HTTP 413 payload limits.
        int limit = Math.min(40, Math.max(1, args.path("limit").asInt(20)));
        String sortBy = text(args, "sort_by");
        if (sortBy.isBlank()) {
            sortBy = "amount_desc";
        }

        List<PaymentDateCustomerCard> filtered = response.getBody().stream()
                .filter(c -> {
                    if (!query.isBlank()) {
                        String name = c.customer() == null ? "" : c.customer().toLowerCase(Locale.ROOT);
                        String phone = c.phoneNumber() == null ? "" : c.phoneNumber().replaceAll("\\D", "");
                        boolean nameOk = name.contains(query);
                        boolean phoneOk = !digits.isEmpty()
                                && (phone.contains(digits) || digits.contains(phone));
                        if (!nameOk && !phoneOk) {
                            return false;
                        }
                    }
                    if (c.totalAmount() < minAmount || c.totalAmount() > maxAmount) {
                        return false;
                    }
                    if (!category.isBlank()) {
                        String cat = c.customerCategory() == null ? "" : c.customerCategory().toLowerCase(Locale.ROOT);
                        if (!cat.equals(category) && !cat.contains(category)) {
                            return false;
                        }
                    }
                    return true;
                })
                .collect(Collectors.toCollection(ArrayList::new));

        Comparator<PaymentDateCustomerCard> cmp = switch (sortBy) {
            case "amount_asc" -> Comparator.comparingDouble(PaymentDateCustomerCard::totalAmount);
            case "name" -> Comparator.comparing(c -> c.customer() == null ? "" : c.customer(), String.CASE_INSENSITIVE_ORDER);
            default -> Comparator.comparingDouble(PaymentDateCustomerCard::totalAmount).reversed();
        };
        filtered.sort(cmp);
        if (filtered.size() > limit) {
            filtered = filtered.subList(0, limit);
        }

        double sum = filtered.stream().mapToDouble(PaymentDateCustomerCard::totalAmount).sum();
        List<Map<String, Object>> customers = filtered.stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("customer", c.customer());
            m.put("phone", c.phoneNumber());
            m.put("totalAmount", c.totalAmount());
            m.put("withinAmount", c.withinAmount());
            m.put("midAmount", c.midAmount());
            m.put("beyondAmount", c.beyondAmount());
            m.put("category", c.customerCategory());
            m.put("nextPaymentDate", c.nextPaymentDate());
            m.put("place", c.place());
            return m;
        }).toList();

        List<List<String>> rows = customers.stream()
                .map(m -> List.of(
                        str(m.get("customer")),
                        str(m.get("phone")),
                        formatInr(((Number) m.get("totalAmount")).doubleValue()),
                        formatInr(((Number) m.get("withinAmount")).doubleValue()),
                        formatInr(((Number) m.get("midAmount")).doubleValue()),
                        formatInr(((Number) m.get("beyondAmount")).doubleValue()),
                        str(m.get("category")),
                        str(m.get("nextPaymentDate"))
                ))
                .toList();

        // Compact payload for the LLM; full table stays in the UI attachment.
        int llmPreview = Math.min(12, customers.size());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", customers.size());
        payload.put("totalOutstanding", sum);
        payload.put("customers", customers.subList(0, llmPreview));
        if (customers.size() > llmPreview) {
            payload.put("truncated", true);
            payload.put("shown", llmPreview);
            payload.put("hint", "Table in UI has all " + customers.size()
                    + " rows. Ask for a smaller filter/limit if you need more detail in chat.");
        }

        return ToolResult.ok(payload, tableAttachment(
                "Outstanding due (" + customers.size() + ")",
                List.of("Customer", "Phone", "Total", "0-45", "46-90", "90+", "Category", "Next payment"),
                rows
        ));
    }

    private ToolResult getCustomerNotes(JsonNode args, SessionInfo session, String authHeader) {
        if (!SessionPermissions.canViewCustomerNotes(session)) {
            return ToolResult.error("Not permitted to view notes.");
        }
        String customer = text(args, "customer");
        String phone = text(args, "phone");
        ResponseEntity<?> response = customerNotesController.getNotes(
                authHeader,
                new CustomerNotesController.CustomerNoteSearchRequest(blankToNull(customer), blankToNull(phone))
        );
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            return ToolResult.error("Could not load notes");
        }
        List<?> notes = (List<?>) response.getBody();
        List<Map<String, Object>> maps = notes.stream()
                .map(n -> objectMapper.convertValue(n, Map.class))
                .map(m -> (Map<String, Object>) m)
                .toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", maps.size());
        payload.put("notes", maps);
        List<List<String>> rows = maps.stream()
                .map(m -> List.of(str(m.get("createdAt")), str(m.get("createdBy")), str(m.get("note"))))
                .toList();
        return ToolResult.ok(payload, tableAttachment(
                "Notes: " + (customer.isBlank() ? phone : customer),
                List.of("Created", "By", "Note"),
                rows
        ));
    }

    private ToolResult exportPdf(JsonNode args, SessionInfo session) {
        String title = text(args, "title");
        if (title.isBlank()) {
            title = "AI Agent Export";
        }
        List<String> columns = new ArrayList<>();
        if (args.path("columns").isArray()) {
            for (JsonNode c : args.path("columns")) {
                columns.add(c.asText(""));
            }
        }
        List<List<String>> rows = new ArrayList<>();
        if (args.path("rows").isArray()) {
            for (JsonNode rowNode : args.path("rows")) {
                if (rows.size() >= 40) {
                    break;
                }
                List<String> row = new ArrayList<>();
                if (rowNode.isArray()) {
                    for (JsonNode cell : rowNode) {
                        row.add(cell.asText(""));
                    }
                }
                rows.add(row);
            }
        }
        if (columns.isEmpty()) {
            return ToolResult.error("columns required");
        }
        byte[] pdf = pdfService.buildTablePdf(title, columns, rows);
        AiAgentExport export = new AiAgentExport();
        export.setId(UUID.randomUUID().toString());
        export.setUserId(session.userId());
        export.setFilename(sanitizeFilename(title) + ".pdf");
        export.setContentType("application/pdf");
        export.setContent(pdf);
        export.setExpiresAt(Instant.now().plus(exportTtlMinutes, ChronoUnit.MINUTES));
        exportRepository.save(export);

        Map<String, Object> attachment = tableAttachment(title, columns, rows);
        attachment.put("type", "pdf");
        attachment.put("downloadId", export.getId());
        attachment.put("filename", export.getFilename());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("downloadId", export.getId());
        payload.put("filename", export.getFilename());
        payload.put("rowCount", rows.size());
        return ToolResult.ok(payload, attachment);
    }

    /** Re-export an existing table attachment as PDF for the current user. */
    public ToolResult exportAttachmentAsPdf(Map<String, Object> tableAttachment, SessionInfo session) {
        if (tableAttachment == null) {
            return ToolResult.error("No table data to export");
        }
        ObjectNode args = objectMapper.createObjectNode();
        args.put("title", str(tableAttachment.get("title")));
        ArrayNode cols = args.putArray("columns");
        Object columnsObj = tableAttachment.get("columns");
        if (columnsObj instanceof List<?> list) {
            for (Object c : list) {
                cols.add(str(c));
            }
        }
        ArrayNode rowsNode = args.putArray("rows");
        Object rowsObj = tableAttachment.get("rows");
        if (rowsObj instanceof List<?> list) {
            for (Object rowObj : list) {
                ArrayNode rowNode = rowsNode.addArray();
                if (rowObj instanceof List<?> cells) {
                    for (Object cell : cells) {
                        rowNode.add(str(cell));
                    }
                }
            }
        }
        return exportPdf(args, session);
    }

    private static boolean matchesCustomer(PaymentDateOverride o, String qLower, String digits) {
        String name = o.customerName() == null ? "" : o.customerName().toLowerCase(Locale.ROOT);
        String place = o.place() == null ? "" : o.place().toLowerCase(Locale.ROOT);
        String phone = o.phoneNumber() == null ? "" : o.phoneNumber().replaceAll("\\D", "");
        boolean nameOk = !qLower.isBlank() && name.contains(qLower);
        boolean placeOk = !qLower.isBlank() && !place.isBlank() && place.contains(qLower);
        boolean phoneOk = !digits.isEmpty() && (phone.contains(digits) || digits.contains(phone));
        return nameOk || placeOk || phoneOk;
    }

    private ObjectNode fn(String name, String description, ObjectNode parameters) {
        ObjectNode tool = objectMapper.createObjectNode();
        tool.put("type", "function");
        ObjectNode function = tool.putObject("function");
        function.put("name", name);
        function.put("description", description);
        function.set("parameters", parameters);
        return tool;
    }

    private ObjectNode prop(String type, String description) {
        return objectMapper.createObjectNode().put("type", type).put("description", description);
    }

    private static Map<String, Object> tableAttachment(String title, List<String> columns, List<List<String>> rows) {
        Map<String, Object> att = new LinkedHashMap<>();
        att.put("type", "table");
        att.put("title", title);
        att.put("columns", columns);
        att.put("rows", rows);
        return att;
    }

    private static String text(JsonNode args, String field) {
        if (args == null || args.isNull()) {
            return "";
        }
        JsonNode n = args.get(field);
        return n == null || n.isNull() ? "" : n.asText("").trim();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String formatInr(double amount) {
        return String.format(Locale.ENGLISH, "₹%,.2f", amount);
    }

    private static String sanitizeFilename(String title) {
        String base = title.replaceAll("[^a-zA-Z0-9._-]+", "_");
        if (base.length() > 60) {
            base = base.substring(0, 60);
        }
        return base.isBlank() ? "export" : base;
    }

    public record ToolResult(boolean ok, Object payload, Map<String, Object> attachment, String error) {
        public static ToolResult ok(Object payload, Map<String, Object> attachment) {
            return new ToolResult(true, payload, attachment, null);
        }

        public static ToolResult error(String error) {
            return new ToolResult(false, Map.of("error", error), null, error);
        }

        /** JSON for the LLM — hard-capped so providers like Groq do not return HTTP 413. */
        public String toJson(ObjectMapper mapper) {
            try {
                if (!ok) {
                    return mapper.writeValueAsString(Map.of("error", error == null ? "failed" : error));
                }
                String json = mapper.writeValueAsString(payload);
                final int max = 10_000;
                if (json.length() <= max) {
                    return json;
                }
                Map<String, Object> compact = new LinkedHashMap<>();
                compact.put("truncated", true);
                compact.put("message", "Result too large for model context; use UI table/PDF. Ask with a smaller limit.");
                if (payload instanceof Map<?, ?> map) {
                    Object count = map.get("count");
                    Object total = map.get("totalOutstanding");
                    if (count != null) {
                        compact.put("count", count);
                    }
                    if (total != null) {
                        compact.put("totalOutstanding", total);
                    }
                }
                compact.put("preview", json.substring(0, Math.min(1500, json.length())));
                return mapper.writeValueAsString(compact);
            } catch (Exception e) {
                return "{\"error\":\"serialize_failed\"}";
            }
        }
    }
}
