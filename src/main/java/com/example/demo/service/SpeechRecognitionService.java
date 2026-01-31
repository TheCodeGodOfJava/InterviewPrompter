package com.example.demo.service;

import com.example.demo.model.VoskResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.vosk.Model;
import org.vosk.Recognizer;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;

@Slf4j
@Service
public class SpeechRecognitionService {

    private static final int SAMPLE_RATE = 16_000;

    private final ObjectMapper objectMapper;
    private final Recognizer recognizer;

    private Process ffmpegProcess;
    private Thread recognitionThread;

    private String lastPartial = "";

    public SpeechRecognitionService(ModelManagerService modelManagerService) throws Exception {
        modelManagerService.checkAndDownloadModels();
        this.objectMapper = new ObjectMapper();
        Model model = new Model("sound/vosk-model-uk-v3");
        this.recognizer = new Recognizer(model, SAMPLE_RATE);
    }

    public synchronized void start() throws Exception {
        if (recognitionThread != null && recognitionThread.isAlive()) {
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
        recognitionThread.setDaemon(true);
        recognitionThread.start();

        log.info("Ukrainian speech recognition started");
    }

    public synchronized void stop() {
        if (recognitionThread != null) {
            recognitionThread.interrupt();
            recognitionThread = null;
        }
        if (ffmpegProcess != null) {
            ffmpegProcess.destroy();
            ffmpegProcess = null;
        }
        log.info("Speech recognition stopped");
    }

    private void runRecognition(InputStream audioStream) {
        byte[] buffer = new byte[4096];

        try {
            int read;
            while ((read = audioStream.read(buffer)) != -1) {
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
        } finally {
            stop();
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
            } catch (Exception ignored) {
                log.error("Failed to extract text from json", ignored);
            }
        }
        return "";
    }
}
