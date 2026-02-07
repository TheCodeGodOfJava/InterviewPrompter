package com.example.demo.service.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * GROK LLM PROVIDER
 * Responsibility: Sends text to xAI and gets a witty response.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ai.provider", havingValue = "grok")
public class GrokLlmProvider implements LlmProvider {

    private final ObjectMapper mapper;

    // Create one client to reuse
    private final HttpClient client = HttpClient.newHttpClient();

    private static final String LLM_URL = "https://api.x.ai/v1/chat/completions";
    private static final String LLM_MODEL = "grok-beta";

    @Value("${grok.api.key}")
    private String apiKey;

    @Override
    public String generateAnswer(String systemPrompt, String userPrompt) {
        try {
            log.info("Sending prompt to Grok...");

            ObjectNode payload = mapper.createObjectNode();
            payload.put("model", LLM_MODEL);
            payload.put("stream", false);

            ArrayNode messages = payload.putArray("messages");
            messages.addObject().put("role", "system").put("content", systemPrompt);
            messages.addObject().put("role", "user").put("content", userPrompt);

            String jsonBody = mapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(LLM_URL))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            // Blocking call (since LlmProvider interface defines it as blocking)
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("xAI Error {}: {}", response.statusCode(), response.body());
                return "I am sorry, my brain is offline.";
            }

            JsonNode root = mapper.readTree(response.body());
            return root.path("choices").get(0).path("message").path("content").asText();

        } catch (Exception e) {
            log.error("xAI Exception", e);
            return "Error processing request.";
        }
    }
}