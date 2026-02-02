package com.example.demo.service;

import com.example.demo.model.SOURCE;
import com.example.demo.model.VoskResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class SpeechRecognitionService {

    // Add near the top of SpeechRecognitionService

    @Getter
    private volatile SOURCE source = SOURCE.STEREO_MIX;

    private static final int SAMPLE_RATE = 16_000;

    private final AiAnswerService aiAnswerService;
    private final LiveTranscriptService liveTranscriptService;
    private final ObjectMapper objectMapper;
    private final Recognizer recognizer;

    private Process ffmpegProcess;
    private Thread recognitionThread;

    // Constructor remains the same (loads model)
    public SpeechRecognitionService(ModelManagerService modelManagerService, LiveTranscriptService liveTranscriptService, AiAnswerService aiAnswerService, ObjectMapper objectMapper) throws Exception {
        modelManagerService.checkAndDownloadModels();
        this.aiAnswerService = aiAnswerService;
        this.liveTranscriptService = liveTranscriptService;
        this.objectMapper = objectMapper;
        Model model = new Model("sound/vosk-model-uk-v3");
        this.recognizer = new Recognizer(model, SAMPLE_RATE);
    }

    @PostConstruct
    public void init() throws Exception {
        // Optional: start with default on boot
        startRecognition();
    }

    // In SpeechRecognitionService

    /**
     * Switches to a new audio source: stops current if running, updates source, starts new.
     * Thread-safe via synchronized.
     */
    public synchronized void switchAudioSource(SOURCE newSource) throws Exception {
        if (newSource == null) {
            throw new IllegalArgumentException("New source cannot be null");
        }

        if (newSource == this.source) {
            log.info("Already using source: {}", newSource);
            return; // No-op if same source
        }

        log.info("Switching audio source from {} to {}", this.source, newSource);

        // 1. Gracefully stop current capture
        this.shutdownSource();

        // 2. Update source **before** starting new one (so getCurrentSource() is correct even if start fails)
        this.source = newSource;

        // 3. Start new recognition with the updated source
        startRecognition();  // renamed helper method
    }

    @PostConstruct
    public void startRecognition() throws Exception {
        if (recognitionThread != null && recognitionThread.isAlive()) {
            log.warn("Recognition already running");
            this.shutdownSource(); // safety net
        }

        ProcessBuilder pb = new ProcessBuilder("ffmpeg", "-f", "dshow", "-i", this.source.getDshowName(), "-ac", "1", "-ar", String.valueOf(SAMPLE_RATE), "-f", "s16le", "pipe:1");

        pb.redirectError(ProcessBuilder.Redirect.DISCARD);

        ffmpegProcess = pb.start();
        InputStream audioStream = ffmpegProcess.getInputStream();

        recognitionThread = new Thread(() -> runRecognition(audioStream), "Vosk-Recognition-" + source.name());
        recognitionThread.setDaemon(true);  // ← Good: JVM won't wait for daemon thread on exit
        recognitionThread.start();

        log.info("Speech recognition started with {}", source);
    }

    private void runRecognition(InputStream audioStream) {
        byte[] buffer = new byte[4096];
        try {
            int read;
            while (!Thread.currentThread().isInterrupted() && (read = audioStream.read(buffer)) != -1) {
                if (recognizer.acceptWaveForm(buffer, read)) {
                    // Final result (utterance complete)
                    String rawJson = recognizer.getResult();
                    String text = extractText(rawJson);
                    if (!text.isBlank()) {
                        String extractedText = text.trim();
                        log.info("[UK] {}", extractedText);
                        liveTranscriptService.appendWord(extractedText);
                        // NEW: Trigger AI processing
                        aiAnswerService.processSpeechWithAI(liveTranscriptService.getCurrentText());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Recognition failed", e);
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

    private void shutdownSource() {
        log.info("Initiating speech recognition shutdown");

        List<Throwable> problems = new ArrayList<>();

        if (recognitionThread != null) {
            recognitionThread.interrupt();
            try {
                recognitionThread.join(5000);  // Wait up to 5 seconds
            } catch (InterruptedException e) {
                log.warn("Interrupted while joining recognition thread", e);
                Thread.currentThread().interrupt();  // Restore interrupt flag
            }

            // After timeout, check if still alive and force stop if necessary
            if (recognitionThread.isAlive()) {
                problems.add(new IllegalStateException("Recognition thread did not stop within 5 seconds – consider it hung!"));
                // No forceful stop for threads (can't "kill" Java threads safely),
                // but since it's daemon, JVM exit will kill it anyway.
            }
        }

        if (ffmpegProcess != null && ffmpegProcess.isAlive()) {
            ffmpegProcess.destroy();
            try {
                if (!ffmpegProcess.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    log.warn("ffmpeg graceful termination timeout → forcing kill");
                    ffmpegProcess.destroyForcibly();
                    if (ffmpegProcess.isAlive()) {
                        problems.add(new IllegalStateException("ffmpeg process still alive after destroyForcibly!"));
                    }
                }
            } catch (InterruptedException e) {
                problems.add(e);
                Thread.currentThread().interrupt();
            }
        }

        if (!problems.isEmpty()) {
            log.warn("Some problems occurred during speech recognition shutdown ({} issues)", problems.size());
            problems.forEach(t -> log.debug("Shutdown issue", t));
        } else {
            log.info("Speech recognition shutdown completed cleanly");
        }
    }

    @PreDestroy
    public void shutdown() {
        this.shutdownSource();
        try {
            recognizer.close();
        } catch (Exception e) {
            log.error("Speech recognizer shutdown failed", e);
        }
    }
}