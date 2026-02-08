package com.example.demo.service;

import com.example.demo.model.SOURCE;
import com.example.demo.service.ai.AiAnswerService;
import com.example.demo.service.ai.AiContextService;
import com.example.demo.service.engine.SpeechRecognizerEngine;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static com.example.demo.service.engine.SpeechRecognizerEngine.SAMPLE_RATE;

@Slf4j
@Service
public class SpeechRecognitionService implements SmartInitializingSingleton {

    private volatile SpeechRecognizerEngine currentEngine;
    private boolean isRunning = false;

    @Getter
    private volatile SOURCE source;
    private final AiAnswerService aiAnswerService;
    private final AiContextService aiContextService;

    private Process ffmpegProcess;
    private Thread recognitionThread;

    // Constructor remains the same (loads model)
    public SpeechRecognitionService(AiContextService aiContextService, AiAnswerService aiAnswerService, SpeechRecognizerEngine currentEngine, @Value("${speech.source:MICROPHONE}") String defaultSource) {
        this.aiAnswerService = aiAnswerService;
        this.aiContextService = aiContextService;
        this.currentEngine = currentEngine;
        // Parse YML string to Enum safely
        try {
            this.source = SOURCE.valueOf(defaultSource.toUpperCase());
            log.info("Initialized Audio Source from Config: {}", this.source);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid speech.source '{}' in YML. Fallback to MICROPHONE.", defaultSource);
            this.source = SOURCE.MICROPHONE;
        }
    }

    public void setEngine(SpeechRecognizerEngine engine) {
        if (this.currentEngine != null) this.currentEngine.close();
        this.currentEngine = engine;
    }

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

    public void startRecognition() throws Exception {
        if (isRunning) {
            log.info("Speech recognition is already active. Ignoring start request.");
            return;
        }
        if (recognitionThread != null && recognitionThread.isAlive()) {
            log.warn("Recognition already running");
            this.shutdownSource(); // safety net
        }

        // --- FIX: ALWAYS APPLY THRESHOLD BEFORE STARTING ---
        log.info("Applying silence threshold: {} for source: {}", source.getThreshold(), source);
        currentEngine.setSilenceThreshold(source.getThreshold());

        ProcessBuilder pb = getProcessBuilder();

        pb.redirectError(ProcessBuilder.Redirect.DISCARD);

        ffmpegProcess = pb.start();
        InputStream audioStream = ffmpegProcess.getInputStream();

        recognitionThread = new Thread(() -> runRecognition(audioStream), "Recognition-" + source.name());
        recognitionThread.setDaemon(true);  // ← Good: JVM won't wait for daemon thread on exit
        recognitionThread.start();

        isRunning = true;
        log.info("Speech recognition started with {}", source);
    }

    private ProcessBuilder getProcessBuilder() {
        List<String> command = new ArrayList<>();
        command.add("ffmpeg");

        // Скрываем лишний мусор из логов, чтобы не забивать буфер
        command.add("-loglevel");
        command.add("quiet");

        command.add("-f");
        command.add("dshow");

        // Устройство ввода
        command.add("-i");
        command.add("audio=" + source.getDshowName());

        // ПАРАМЕТРЫ КОНВЕРТАЦИИ (Критически важно!)
        command.add("-ar");
        command.add(String.valueOf(SAMPLE_RATE)); // 16000
        command.add("-ac");
        command.add("1");                         // Смешиваем стерео в моно

        command.add("-f");
        command.add("s16le");                     // Raw PCM 16-bit
        command.add("pipe:1");                    // Вывод в стандартный поток

        return new ProcessBuilder(command);
    }

    // inside SpeechRecognitionService.java

    private void runRecognition(InputStream audioStream) {
        byte[] buffer = new byte[4096];

        // 1. Define what happens when text is recognized (The Callback)
        Consumer<String> onTextRecognized = (text) -> {
            if (text != null && !text.isBlank()) {
                log.info("Async Result: {}", text);
                // STEP 1: Add what the user said to the Chat History
                aiContextService.addMessage("user", text); // CHANGED

                // STEP 2: Ask AI to answer (it will pull history internally)
                // We pass the text just for logging/triggering,
                // but the service actually uses the full list.
                aiAnswerService.processSpeechWithAI(text);
            }
        };

        try {
            int read;
            // 2. The loop just pumps audio. It NEVER waits.
            while (!Thread.currentThread().isInterrupted() && (read = audioStream.read(buffer)) != -1) {
                // We pass the callback into the engine
                currentEngine.processAudio(buffer, read, onTextRecognized);
            }
        } catch (Exception e) {
            log.error("Recognition failed", e);
        }
    }


    private void shutdownSource() {
        log.info("Initiating speech recognition shutdown");
        isRunning = false;

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
        this.setEngine(null);
    }

    @Override
    public void afterSingletonsInstantiated() {
        try {
            startRecognition();
        } catch (Exception e) {
            log.info("Unable to start speech recognition", e);
        }
    }
}