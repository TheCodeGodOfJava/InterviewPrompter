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

    public synchronized void switchAudioSource(SOURCE newSource) throws Exception {
        if (newSource == null) {
            throw new IllegalArgumentException("New source cannot be null");
        }

        if (newSource == this.source) {
            log.info("Already using source: {}", newSource);
            return;
        }

        log.info("Switching audio source from {} to {}", this.source, newSource);

        // 1. Gracefully stop current capture
        this.shutdownSource();

        // 2. Update source **before** starting new one (so getCurrentSource() is correct even if start fails)
        this.source = newSource;

        // 3. Start new recognition with the updated source
        startRecognition();
    }

    public void startRecognition() throws Exception {
        if (isRunning) {
            log.info("Speech recognition is already active. Ignoring start request.");
            return;
        }
        if (recognitionThread != null && recognitionThread.isAlive()) {
            log.warn("Recognition already running");
            this.shutdownSource();
        }

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

        command.add("-loglevel");
        command.add("quiet");

        command.add("-f");
        command.add("dshow");

        command.add("-i");
        command.add("audio=" + source.getDshowName());

        command.add("-ar");
        command.add(String.valueOf(SAMPLE_RATE)); // 16000
        command.add("-ac");
        command.add("1");

        command.add("-f");
        command.add("s16le");                     // Raw PCM 16-bit
        command.add("pipe:1");

        return new ProcessBuilder(command);
    }

    private void runRecognition(InputStream audioStream) {
        byte[] buffer = new byte[4096];

        // 1. Define what happens when text is recognized (The Callback)
        Consumer<String> onTextRecognized = (text) -> {
            if (text != null && !text.isBlank()) {
                log.info("Async Result: {}", text);
                // STEP 1: Add what the user said to the Chat History
                aiContextService.addMessage("user", text);

                // STEP 2: Ask AI to answer (it will pull history internally)
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
        log.info("Initiating speech recognition shutdown...");
        isRunning = false;
        List<Throwable> problems = new ArrayList<>();

        // -------------------------------------------------
        // STEP 1: Cut the data source (ffmpeg)
        // This forces the InputStream.read() in the thread to unblock (return -1 or throw IOException)
        // -------------------------------------------------
        if (ffmpegProcess != null) {
            try {
                // 1. Kill child processes (if any)
                ffmpegProcess.descendants().forEach(ProcessHandle::destroy);

                // 2. Kill the main process
                if (ffmpegProcess.isAlive()) {
                    ffmpegProcess.destroy(); // Try graceful SIGTERM

                    // Wait briefly for it to die
                    if (!ffmpegProcess.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                        log.warn("FFmpeg did not stop gracefully. Forcing kill...");
                        ffmpegProcess.destroyForcibly(); // SIGKILL
                    }
                }
            } catch (InterruptedException e) {
                problems.add(e);
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                problems.add(e);
            }
        }

        // -------------------------------------------------
        // STEP 2: Close the InputStream explicitly
        // This guarantees the thread throws an IOException if it's still reading
        // -------------------------------------------------
        if (ffmpegProcess != null) {
            try {
                ffmpegProcess.getInputStream().close();
            } catch (Exception e) {
                log.info("Ffmpeg system shutdown.", e);
            }
        }

        // -------------------------------------------------
        // STEP 3: Stop the Java Thread
        // Now that the stream is broken, the thread should be exiting its loop naturally.
        // -------------------------------------------------
        if (recognitionThread != null && recognitionThread.isAlive()) {
            recognitionThread.interrupt(); // Set flag just in case
            try {
                recognitionThread.join(2000); // Should finish almost instantly now
            } catch (InterruptedException e) {
                log.warn("Interrupted while joining recognition thread", e);
                Thread.currentThread().interrupt();
            }

            if (recognitionThread.isAlive()) {
                problems.add(new IllegalStateException("Recognition thread stuck even after Process died!"));
            }
        }

        // -------------------------------------------------
        // STEP 4: Reporting
        // -------------------------------------------------
        if (!problems.isEmpty()) {
            log.warn("Shutdown completed with {} issues.", problems.size());
            problems.forEach(t -> log.debug("Shutdown issue: ", t));
        } else {
            log.info("Speech recognition shutdown completed cleanly.");
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