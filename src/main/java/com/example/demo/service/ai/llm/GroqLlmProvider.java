package com.example.demo.service.ai.llm;

import com.example.demo.model.ChatMessage;
import com.example.demo.model.dto.GroqMessage;
import com.example.demo.model.dto.GroqRequest;
import com.example.demo.model.dto.GroqResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ai.provider", havingValue = "groq") // Fixed value
public class GroqLlmProvider implements LlmProvider {

    private final ObjectMapper mapper;

    // Create one client to reuse (Java 21 best practice)
    private final HttpClient client = HttpClient.newHttpClient();

    private static final String LLM_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final String LLM_MODEL = "llama-3.3-70b-versatile";

    @Value("${groq.api.key}")
    private String apiKey;


    @Override
    public String generateAnswer(List<ChatMessage> history) {
        try {
            // STEP 1: Convert your Domain Objects (ChatMessage) to DTOs (GroqMessage)
            List<GroqMessage> apiMessages = history.stream()
                    .map(msg -> new GroqMessage(msg.role(), msg.content()))
                    .toList();

            // STEP 2: Create the Request Object
            GroqRequest requestPayload = new GroqRequest(
                    LLM_MODEL,
                    0.6,
                    false,
                    apiMessages
            );

            // STEP 3: Auto-Magic Serialization (Object -> JSON String)
            String jsonBody = mapper.writeValueAsString(requestPayload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(LLM_URL))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            // STEP 4: Send Request
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Groq API Error {}: {}", response.statusCode(), response.body());
                return "I am sorry, Groq is offline.";
            }

            // STEP 5: Auto-Magic Deserialization (JSON String -> Object)
            // We read the JSON directly into our Record structure
            GroqResponse responseObj = mapper.readValue(response.body(), GroqResponse.class);

            // Access data using standard Java methods (no more .path("choices").get(0)...)
            return responseObj.choices().getFirst().message().content();

        } catch (Exception e) {
            log.error("Groq Exception", e);
            return "Error processing request.";
        }
    }
}