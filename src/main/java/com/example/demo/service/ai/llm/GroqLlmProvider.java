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
 * GROQ LLM PROVIDER
 * Responsibility: Sends text to Groq and gets a witty response.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ai.provider", havingValue = "")
public class GroqLlmProvider implements LlmProvider {

    private final ObjectMapper mapper;

    // Create one client to reuse
    private final HttpClient client = HttpClient.newHttpClient();

    // 1. URL for Groq
    private static final String LLM_URL = "https://api.groq.com/openai/v1/chat/completions";

    // 2. Model hosted on Groq (Free)
    private static final String LLM_MODEL = "llama-3.3-70b-versatile";

    @Value("${groq.api.key}")
    private String apiKey;

    @Override
    public String generateAnswer(String systemPrompt, String userPrompt) {
        try {
            log.info("Sending prompt to Groq (Llama 3)...");

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
                log.error("Groq API Error {}: {}", response.statusCode(), response.body());
                return "I am sorry, Groq is offline.";
            }

            JsonNode root = mapper.readTree(response.body());
            return root.path("choices").get(0).path("message").path("content").asText();

        } catch (Exception e) {
            log.error("Groq Exception", e);
            return "Error processing request.";
        }
    }
}