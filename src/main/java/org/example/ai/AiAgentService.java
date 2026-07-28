package org.example.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.auth.SessionInfo;
import org.example.auth.SessionPermissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AiAgentService {

    private static final Logger logger = LoggerFactory.getLogger(AiAgentService.class);

    /**
     * Domain-only data agent. Must refuse general chat / open-world questions.
     */
    private static final String SYSTEM_PROMPT = """
            You are Ruufashion's BUSINESS DATA AGENT — not a general assistant, not a chatbot.

            SCOPE (only these):
            - Customer outstanding dues / ageing amounts
            - Customer search (name / phone)
            - Outstanding due lists and filters
            - Customer ledger
            - Customer notes
            - Exporting tool results as PDF

            HARD RULES:
            1. You are NOT a general AI. Refuse greetings and off-topic chat such as "how are you?", jokes, weather, news, coding help, life advice, or anything unrelated to this app's customer/dues data.
            2. For off-topic asks, reply briefly that you only answer business data questions in this app, and suggest examples (due for a customer, list outstanding, export PDF). Do NOT continue a friendly conversation.
            3. Answer data questions ONLY from tool results. Never invent customers, phones, amounts, or notes.
            4. If multiple customers match, ask which one before concluding.
            5. Use tools whenever you need live data. Prefer concise answers with INR amounts.
            6. For list_outstanding_due always pass a small limit (10–20, never above 40).
            7. When exporting PDF, pass at most ~40 rows from tool data.
            8. If a tool fails due to permissions, say they need the matching page permission.
            9. Do not role-play, do not be witty, do not answer general knowledge.
            """;

    private final AiAgentConversationRepository conversationRepository;
    private final AiAgentMessageRepository messageRepository;
    private final AiAgentAuditRepository auditRepository;
    private final AiAgentExportRepository exportRepository;
    private final AiAgentToolExecutor toolExecutor;
    private final AiAgentLlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final int maxToolRounds;
    private final int historyMessageLimit;

    public AiAgentService(
            AiAgentConversationRepository conversationRepository,
            AiAgentMessageRepository messageRepository,
            AiAgentAuditRepository auditRepository,
            AiAgentExportRepository exportRepository,
            AiAgentToolExecutor toolExecutor,
            AiAgentLlmClient llmClient,
            ObjectMapper objectMapper,
            @Value("${ai.agent.enabled:true}") boolean enabled,
            @Value("${ai.agent.max-tool-rounds:6}") int maxToolRounds,
            @Value("${ai.agent.history-message-limit:40}") int historyMessageLimit
    ) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.auditRepository = auditRepository;
        this.exportRepository = exportRepository;
        this.toolExecutor = toolExecutor;
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.maxToolRounds = Math.max(1, maxToolRounds);
        this.historyMessageLimit = Math.max(4, Math.min(historyMessageLimit, 20));
    }

    public Map<String, Object> status(SessionInfo session) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", enabled);
        status.put("llmConfigured", llmClient.isConfigured());
        status.put("model", llmClient.isConfigured() ? llmClient.model() : null);
        status.put("mode", llmClient.isConfigured() ? "llm" : "unconfigured");
        status.put("ready", enabled && llmClient.isConfigured());
        List<String> capabilities = new ArrayList<>();
        if (SessionPermissions.canAccessOutstandingPage(session)) {
            capabilities.add("list_outstanding_due");
            capabilities.add("search_customers");
        }
        if (SessionPermissions.canAccessDetailsOrOutstanding(session)) {
            capabilities.add("get_customer_due");
            capabilities.add("get_customer_ledger");
            capabilities.add("search_customers");
        }
        if (SessionPermissions.canViewCustomerNotes(session)) {
            capabilities.add("get_customer_notes");
        }
        capabilities.add("export_pdf");
        status.put("capabilities", capabilities.stream().distinct().toList());
        status.put("suggestions", suggestions(session));
        if (!llmClient.isConfigured()) {
            status.put("setupHint",
                    "Set ai.agent.api-key. Free local option: Groq key from https://console.groq.com/keys "
                            + "with base-url https://api.groq.com/openai/v1");
        }
        return status;
    }

    public List<String> suggestions(SessionInfo session) {
        List<String> tips = new ArrayList<>();
        if (SessionPermissions.canAccessDetailsOrOutstanding(session)) {
            tips.add("What is the due amount for customer <name>?");
            tips.add("Show ledger for <name or phone>");
        }
        if (SessionPermissions.canAccessOutstandingPage(session)) {
            tips.add("List customers with outstanding due");
            tips.add("Top 10 customers by outstanding amount");
            tips.add("Customers with due above 50000");
        }
        if (SessionPermissions.canViewCustomerNotes(session)) {
            tips.add("Show notes for <customer>");
        }
        tips.add("Export that as PDF");
        return tips;
    }

    public List<AiAgentConversation> listConversations(String userId) {
        return conversationRepository.findByUserIdOrderByUpdatedAtDesc(userId);
    }

    public List<AiAgentMessage> listMessages(String conversationId, String userId) {
        return messageRepository.findByConversationIdAndUserIdOrderByCreatedAtAsc(conversationId, userId);
    }

    public void deleteConversation(String conversationId, String userId) {
        conversationRepository.findByIdAndUserId(conversationId, userId).ifPresent(c -> {
            messageRepository.deleteByConversationIdAndUserId(conversationId, userId);
            conversationRepository.deleteByIdAndUserId(conversationId, userId);
        });
    }

    public AiAgentExport getExport(String exportId, String userId) {
        return exportRepository.findByIdAndUserId(exportId, userId)
                .filter(e -> e.getExpiresAt() == null || e.getExpiresAt().isAfter(Instant.now()))
                .orElse(null);
    }

    public ChatResult chat(SessionInfo session, String authHeader, String conversationId, String userMessage) {
        if (!enabled) {
            throw new IllegalStateException("AI agent is disabled");
        }
        if (!llmClient.isConfigured()) {
            throw new IllegalStateException(
                    "AI agent requires an LLM. Set AI_AGENT_API_KEY (and optional AI_AGENT_BASE_URL / AI_AGENT_MODEL).");
        }
        String message = userMessage == null ? "" : userMessage.trim();
        if (message.isBlank()) {
            throw new IllegalArgumentException("Message is required");
        }
        if (message.length() > 4000) {
            throw new IllegalArgumentException("Message is too long");
        }
        String userId = session.userId();
        if (userId == null || userId.isBlank()) {
            throw new IllegalStateException("Session is missing user id");
        }

        Instant now = Instant.now();
        AiAgentConversation conversation = resolveConversation(userId, conversationId, message, now);

        AiAgentMessage userMsg = new AiAgentMessage();
        userMsg.setId(UUID.randomUUID().toString());
        userMsg.setConversationId(conversation.getId());
        userMsg.setUserId(userId);
        userMsg.setRole("user");
        userMsg.setContent(message);
        userMsg.setCreatedAt(now);
        messageRepository.save(userMsg);

        List<AiAgentMessage> history = messageRepository
                .findByConversationIdAndUserIdOrderByCreatedAtAsc(conversation.getId(), userId);

        AgentTurn turn;
        try {
            turn = runLlmAgent(session, authHeader, history);
        } catch (Exception e) {
            logger.warn("LLM agent failed: {}", e.toString());
            throw new IllegalStateException(
                    e.getMessage() == null ? "LLM request failed" : e.getMessage(), e);
        }

        AiAgentMessage assistantMsg = new AiAgentMessage();
        assistantMsg.setId(UUID.randomUUID().toString());
        assistantMsg.setConversationId(conversation.getId());
        assistantMsg.setUserId(userId);
        assistantMsg.setRole("assistant");
        assistantMsg.setContent(turn.reply());
        assistantMsg.setAttachments(turn.attachments().isEmpty() ? null : turn.attachments());
        assistantMsg.setToolsUsed(turn.toolsUsed().isEmpty() ? null : turn.toolsUsed());
        assistantMsg.setCreatedAt(Instant.now());
        messageRepository.save(assistantMsg);

        conversation.setUpdatedAt(Instant.now());
        conversationRepository.save(conversation);

        AiAgentAuditLog audit = new AiAgentAuditLog();
        audit.setId(UUID.randomUUID().toString());
        audit.setUserId(userId);
        audit.setUsername(session.displayName());
        audit.setConversationId(conversation.getId());
        audit.setUserMessage(message.length() > 500 ? message.substring(0, 500) : message);
        audit.setToolsUsed(turn.toolsUsed());
        audit.setMode("llm");
        audit.setCreatedAt(Instant.now());
        auditRepository.save(audit);

        return new ChatResult(conversation, assistantMsg, "llm");
    }

    private AgentTurn runLlmAgent(SessionInfo session, String authHeader, List<AiAgentMessage> history) {
        ArrayNode tools = toolExecutor.toolDefinitions(session);
        ArrayNode messages = objectMapper.createArrayNode();
        messages.add(objectMapper.createObjectNode().put("role", "system").put("content", SYSTEM_PROMPT));

        List<AiAgentMessage> trimmed = history;
        if (history.size() > historyMessageLimit) {
            trimmed = history.subList(history.size() - historyMessageLimit, history.size());
        }
        for (AiAgentMessage m : trimmed) {
            if ("user".equals(m.getRole()) || "assistant".equals(m.getRole())) {
                String content = m.getContent() == null ? "" : m.getContent();
                if (content.length() > 2500) {
                    content = content.substring(0, 2500) + "…[truncated]";
                }
                messages.add(objectMapper.createObjectNode()
                        .put("role", m.getRole())
                        .put("content", content));
            }
        }

        List<String> toolsUsed = new ArrayList<>();
        List<Map<String, Object>> attachments = new ArrayList<>();

        for (int round = 0; round < maxToolRounds; round++) {
            JsonNode response = llmClient.chat(messages, tools);
            JsonNode choice = response.path("choices").path(0).path("message");
            if (choice.isMissingNode()) {
                throw new IllegalStateException("Empty LLM response");
            }

            ArrayNode toolCalls = choice.has("tool_calls") && choice.get("tool_calls").isArray()
                    ? (ArrayNode) choice.get("tool_calls")
                    : null;

            if (toolCalls == null || toolCalls.isEmpty()) {
                String content = choice.path("content").asText("").trim();
                if (content.isBlank()) {
                    content = "I can only help with customer dues, lists, notes, and PDF export from this app.";
                }
                return new AgentTurn(content, attachments, toolsUsed);
            }

            messages.add(choice);

            for (JsonNode call : toolCalls) {
                String id = call.path("id").asText(UUID.randomUUID().toString());
                String name = call.path("function").path("name").asText("");
                String argJson = call.path("function").path("arguments").asText("{}");
                JsonNode args;
                try {
                    args = objectMapper.readTree(argJson);
                } catch (Exception e) {
                    args = objectMapper.createObjectNode();
                }
                toolsUsed.add(name);
                AiAgentToolExecutor.ToolResult result = toolExecutor.execute(name, args, session, authHeader);
                if (result.attachment() != null) {
                    attachments.add(result.attachment());
                }
                ObjectNode toolMsg = objectMapper.createObjectNode();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", id);
                toolMsg.put("content", result.toJson(objectMapper));
                messages.add(toolMsg);
            }
        }

        return new AgentTurn(
                "I gathered data but hit the tool-round limit. Ask me to continue or export as PDF.",
                attachments,
                toolsUsed
        );
    }

    private AiAgentConversation resolveConversation(String userId, String conversationId, String message, Instant now) {
        if (conversationId != null && !conversationId.isBlank()) {
            return conversationRepository.findByIdAndUserId(conversationId, userId)
                    .orElseGet(() -> createConversation(userId, message, now));
        }
        return createConversation(userId, message, now);
    }

    private AiAgentConversation createConversation(String userId, String message, Instant now) {
        AiAgentConversation c = new AiAgentConversation();
        c.setId(UUID.randomUUID().toString());
        c.setUserId(userId);
        c.setTitle(titleFrom(message));
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return conversationRepository.save(c);
    }

    private static String titleFrom(String message) {
        String t = message.replaceAll("\\s+", " ").trim();
        if (t.length() > 60) {
            return t.substring(0, 57) + "...";
        }
        return t.isBlank() ? "New chat" : t;
    }

    public record ChatResult(AiAgentConversation conversation, AiAgentMessage assistantMessage, String mode) {
    }

    private record AgentTurn(String reply, List<Map<String, Object>> attachments, List<String> toolsUsed) {
    }
}
