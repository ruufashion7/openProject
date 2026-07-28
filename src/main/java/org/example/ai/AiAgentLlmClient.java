package org.example.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * OpenAI-compatible Chat Completions client (tools / function calling).
 */
@Component
public class AiAgentLlmClient {

    private static final Logger logger = LoggerFactory.getLogger(AiAgentLlmClient.class);

    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final RestClient restClient;

    public AiAgentLlmClient(
            ObjectMapper objectMapper,
            @Value("${ai.agent.api-key:}") String apiKey,
            @Value("${ai.agent.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${ai.agent.model:gpt-4o-mini}") String model
    ) {
        this.objectMapper = objectMapper;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.openai.com/v1" : baseUrl.replaceAll("/$", "");
        this.model = model == null || model.isBlank() ? "gpt-4o-mini" : model.trim();
        this.restClient = RestClient.builder().baseUrl(this.baseUrl).build();
    }

    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    public String model() {
        return model;
    }

    public JsonNode chat(ArrayNode messages, ArrayNode tools) {
        if (!isConfigured()) {
            throw new IllegalStateException("AI_AGENT_API_KEY is not configured");
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.set("messages", messages);
        if (tools != null && !tools.isEmpty()) {
            body.set("tools", tools);
            body.put("tool_choice", "auto");
        }
        body.put("temperature", 0.1);

        try {
            String response = restClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return objectMapper.readTree(response);
        } catch (RestClientResponseException e) {
            String responseBody = e.getResponseBodyAsString();
            logger.warn("LLM HTTP {}: {}", e.getStatusCode().value(), responseBody);
            int code = e.getStatusCode().value();
            if (code == 401 || code == 403) {
                throw new IllegalStateException(
                        "LLM provider rejected the API key (HTTP " + code + "). Check ai.agent.api-key.");
            }
            if (code == 429) {
                throw new IllegalStateException("LLM rate limit / quota exceeded. Try again later.");
            }
            if (code == 413) {
                throw new IllegalStateException(
                        "Request too large for the LLM provider (HTTP 413). Start a new chat and ask with a smaller list (e.g. top 10).");
            }
            throw new IllegalStateException("LLM request failed (HTTP " + code + ")");
        } catch (Exception e) {
            throw new IllegalStateException("LLM request failed: " + e.getMessage(), e);
        }
    }
}
