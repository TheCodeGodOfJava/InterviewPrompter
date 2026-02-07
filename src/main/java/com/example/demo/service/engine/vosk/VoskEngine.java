package com.example.demo.service.engine.vosk;

import com.example.demo.model.VoskResult;
import com.example.demo.service.engine.SpeechRecognizerEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.IOException;
import java.util.function.Consumer;

@Slf4j
@Service
@ConditionalOnProperty(name = "speech.engine", havingValue = "vosk", matchIfMissing = true)
public class VoskEngine implements SpeechRecognizerEngine {
    private final Recognizer recognizer;
    private final ObjectMapper objectMapper;

    // Vosk needs a smaller buffer than Whisper, usually 4096 is fine.
    // Ensure this matches what you send from the Service.

    public VoskEngine(VoskModelManagerService modelManagerService, ObjectMapper objectMapper) throws IOException {
        modelManagerService.checkAndDownloadModels();
        // Ensure path matches your actual file structure
        Model model = new Model("sound/vosk-model-uk-v3");
        this.recognizer = new Recognizer(model, SAMPLE_RATE);
        this.objectMapper = objectMapper;
    }

    /**
     * Updated to match the new Interface signature.
     * Note: Vosk is fast, so we run it synchronously here.
     * We do NOT use CompletableFuture because Vosk requires strict sequential ordering of bytes.
     */
    @Override
    public void processAudio(byte[] buffer, int bytesRead, Consumer<String> onResult) {
        // acceptWaveForm returns true if a silence/pause was detected and a full sentence is ready
        if (recognizer.acceptWaveForm(buffer, bytesRead)) {
            String rawJson = recognizer.getResult();
            String text = extractText(rawJson);

            // Invoke the callback immediately
            if (!text.isBlank()) {
                onResult.accept(text);
            }
        }
    }

    private String extractText(String json) {
        if (json == null || json.isBlank()) return "";
        try {
            /* * FIX: Vosk (Native) sends UTF-8 bytes.
             * Windows JVM sees bytes and mistakenly decodes them as Windows-1251.
             * We convert it back to bytes using Windows-1251 and re-read as UTF-8.
             */
            byte[] bytes = json.getBytes(java.nio.charset.Charset.forName("Windows-1251"));
            String fixedJson = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);

            VoskResult result = objectMapper.readValue(fixedJson, VoskResult.class);
            return result.text() != null ? result.text() : "";
        } catch (Exception e) {
            // Fallback logic
            try {
                VoskResult result = objectMapper.readValue(json, VoskResult.class);
                return result.text() != null ? result.text() : "";
            } catch (Exception error) {
                log.error("Failed to extract text from json", error);
            }
        }
        return "";
    }

    @Override
    public void close() {
        try {
            recognizer.close();
        } catch (Exception e) {
            log.error("Speech recognizer shutdown failed", e);
        }
    }
}