package com.example.demo.service;

import com.example.demo.model.VoskResult;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.vosk.Model;
import org.vosk.Recognizer;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class SpeechRecognitionService {

    private static final int SAMPLE_RATE = 16_000;

    private final ObjectMapper objectMapper;
    private final Recognizer recognizer;

    private Process ffmpegProcess;
    private Thread recognitionThread;

    private String lastPartial = "";

    // Constructor remains the same (loads model)
    public SpeechRecognitionService(ModelManagerService modelManagerService,
                                    ObjectMapper objectMapper) throws Exception {
        modelManagerService.checkAndDownloadModels();
        this.objectMapper = objectMapper;
        Model model = new Model("sound/vosk-model-uk-v3");
        this.recognizer = new Recognizer(model, SAMPLE_RATE);
    }

    @PostConstruct
    public void startRecognition() throws Exception {
        if (recognitionThread != null && recognitionThread.isAlive()) {
            log.warn("Recognition already running");
            return;
        }

        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg",
                "-f", "dshow",
                "-i", "audio=Stereo Mix (Realtek(R) Audio)",
                "-ac", "1",
                "-ar", String.valueOf(SAMPLE_RATE),
                "-f", "s16le",
                "pipe:1"
        );

        pb.redirectError(ProcessBuilder.Redirect.DISCARD);

        ffmpegProcess = pb.start();
        InputStream audioStream = ffmpegProcess.getInputStream();

        recognitionThread = new Thread(() -> runRecognition(audioStream), "Vosk-Recognition");
        recognitionThread.setDaemon(true);  // ← Good: JVM won't wait for daemon thread on exit
        recognitionThread.start();

        log.info("Ukrainian continuous speech recognition started");
    }

    private void runRecognition(InputStream audioStream) {
        byte[] buffer = new byte[4096];
        try {
            int read;
            while (!Thread.currentThread().isInterrupted() && (read = audioStream.read(buffer)) != -1) {
                if (recognizer.acceptWaveForm(buffer, read)) {
                    String rawJson = recognizer.getResult();
                    // We fix the encoding immediately upon receiving it from the native library
                    String text = extractText(rawJson);
                    if (!text.isBlank()) {
                        log.info("[UK] {}", text);
                        lastPartial = "";
                    }
                } else {
                    String rawJson = recognizer.getPartialResult();
                    String partial = extractText(rawJson);
                    if (!partial.isBlank() && !partial.equals(lastPartial)) {
                        log.info("[UK partial] {}", partial);
                        lastPartial = partial;
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

    @PreDestroy
    public void shutdown() {
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

        try {
            recognizer.close();
        } catch (Exception e) {
            problems.add(e);
        }

        if (!problems.isEmpty()) {
            log.warn("Some problems occurred during speech recognition shutdown ({} issues)", problems.size());
            problems.forEach(t -> log.debug("Shutdown issue", t));
        } else {
            log.info("Speech recognition shutdown completed cleanly");
        }
    }
}