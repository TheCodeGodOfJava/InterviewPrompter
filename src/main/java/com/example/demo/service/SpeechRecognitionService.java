package com.example.demo.service;

import com.example.demo.model.SOURCE;
import com.example.demo.service.engine.SpeechRecognizerEngine;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static com.example.demo.service.engine.SpeechRecognizerEngine.SAMPLE_RATE;

@Slf4j
@Service
public class SpeechRecognitionService {

    private volatile SpeechRecognizerEngine currentEngine;

    @Getter
    private volatile SOURCE source = SOURCE.STEREO_MIX;
    private final AiAnswerService aiAnswerService;
    private final LiveTranscriptService liveTranscriptService;

    private Process ffmpegProcess;
    private Thread recognitionThread;

    // Constructor remains the same (loads model)
    public SpeechRecognitionService(LiveTranscriptService liveTranscriptService, AiAnswerService aiAnswerService, SpeechRecognizerEngine currentEngine) {
        this.aiAnswerService = aiAnswerService;
        this.liveTranscriptService = liveTranscriptService;
        this.currentEngine = currentEngine;
    }

    public void setEngine(SpeechRecognizerEngine engine) {
        if (this.currentEngine != null) this.currentEngine.close();
        this.currentEngine = engine;
    }

    @PostConstruct
    public void init() throws Exception {
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

        ProcessBuilder pb = getProcessBuilder();

        pb.redirectError(ProcessBuilder.Redirect.DISCARD);

        ffmpegProcess = pb.start();
        InputStream audioStream = ffmpegProcess.getInputStream();

        recognitionThread = new Thread(() -> runRecognition(audioStream), "Vosk-Recognition-" + source.name());
        recognitionThread.setDaemon(true);  // ← Good: JVM won't wait for daemon thread on exit
        recognitionThread.start();

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

    private void runRecognition(InputStream audioStream) {
        byte[] buffer = new byte[4096];
        try {
            int read;
            while (!Thread.currentThread().isInterrupted() && (read = audioStream.read(buffer)) != -1) {
                String result = currentEngine.processAudio(buffer, read);

                if (result != null && !result.isBlank()) {
                    log.info("Recognized: {}", result);
                    liveTranscriptService.appendWord(result);
                    aiAnswerService.processSpeechWithAI(liveTranscriptService.getCurrentText());
                }
            }
        } catch (Exception e) {
            log.error("Recognition failed", e);
        }
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
        this.setEngine(null);
    }
}