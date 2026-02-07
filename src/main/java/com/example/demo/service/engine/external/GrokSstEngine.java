package com.example.demo.service.engine.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * INTELLIGENT ENGINE
 * Input: Audio (WAV)
 * Output: AI Answer (String)
 * * This engine chains the Speech-to-Text and the Text-to-AI logic
 * internally, so the main app only ever sees the final answer.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "speech.engine", havingValue = "grok")
public class GrokSstEngine extends AbstractSpeechEngine {

    private final HttpClient client;
    private final ObjectMapper mapper;

    // 1. SETTINGS FOR SPEECH RECOGNITION (e.g., Groq or OpenAI)
    // We use Groq here because it's fast, but you can swap the URL for OpenAI
    private static final String STT_URL = "https://api.groq.com/openai/v1/audio/transcriptions";

    @Value("${grok.api.key}")
    private String apiKey;

    public GrokSstEngine(ObjectMapper mapper) {
        this.mapper = mapper;
        this.client = HttpClient.newHttpClient();
    }

    @Override
    protected CompletableFuture<String> transcribe(byte[] wavData) {
        return sendAudioToCloud(wavData);
    }

    private CompletableFuture<String> sendAudioToCloud(byte[] wavData) {
        String boundary = "Boundary-" + UUID.randomUUID();

        // Whisper-large-v3-turbo is fast and accurate on Groq
        Map<String, String> params = Map.of("model", "whisper-large-v3-turbo");
        byte[] body = buildMultipartBody(wavData, boundary, params);

        HttpRequest request = newRequest(STT_URL)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        log.error("Groq STT Error {}: {}", response.statusCode(), response.body());
                        return null;
                    }
                    try {
                        // Return the actual text the user said
                        return mapper.readTree(response.body()).path("text").asText();
                    } catch (Exception e) {
                        log.error("Groq JSON Error", e);
                        return null;
                    }
                });
    }
}