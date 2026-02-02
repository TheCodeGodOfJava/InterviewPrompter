package com.example.demo.service.engine;

import com.example.demo.model.VoskResult;
import com.example.demo.service.ModelManagerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.IOException;

@Slf4j
public class VoskEngine implements SpeechRecognizerEngine {
    private final Recognizer recognizer;
    private final ObjectMapper objectMapper;

    public VoskEngine(ModelManagerService modelManagerService, ObjectMapper objectMapper) throws IOException {
        modelManagerService.checkAndDownloadModels();
        Model model = new Model("sound/vosk-model-uk-v3");
        this.recognizer = new Recognizer(model, SAMPLE_RATE);
        this.objectMapper = objectMapper;
    }

    @Override
    public String processAudio(byte[] buffer, int bytesRead) {
        if (recognizer.acceptWaveForm(buffer, bytesRead)) {
            String rawJson = recognizer.getResult();
            return extractText(rawJson);
        }
        return null;
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
            return result.text() != null ? result.text() : result.partial();
        } catch (Exception e) {
            // Fallback to original if fix fails
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