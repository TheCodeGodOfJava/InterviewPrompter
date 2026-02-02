// src/main/java/com/example/demo/service/OllamaManagerService.java
package com.example.demo.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class AiManagerService {

    private static final String OLLAMA_API_URL = "http://localhost:11434/api/tags";
    private static final long STARTUP_TIMEOUT_SECONDS = 15;

    @PostConstruct
    public void initializeOllama() {
        if (isOllamaRunning()) {
            log.info("Ollama is already running and accessible on port 11434");
            return;
        }

        log.warn("Ollama is not running. Attempting automatic startup...");

        boolean started = startOllamaServer();
        if (started) {
            log.info("Ollama successfully started automatically");
        } else {
            log.error("""
                    Failed to automatically start Ollama!
                    Please start it manually:
                      1. Open a terminal
                      2. Execute: ollama serve
                    Or install Ollama as a service:
                      ollama service install
                      ollama service start
                    """);
        }
    }

    /**
     * Checks if the Ollama server is responding to requests (port 11434)
     */
    private boolean isOllamaRunning() {
        try {
            URL url = URI.create(OLLAMA_API_URL).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2000); // 2 seconds
            conn.setReadTimeout(2000);

            int responseCode = conn.getResponseCode();
            return responseCode == 200;
        } catch (IOException e) {
            log.error("Failed to connect to Ollama API!");
            return false;
        }
    }

    /**
     * Attempts to start 'ollama serve' as a separate process
     */
    private boolean startOllamaServer() {
        try {
            // Path to ollama.exe on Windows (standard installation path)
            String ollamaPath = System.getProperty("user.home") + "\\AppData\\Local\\Programs\\Ollama\\ollama.exe";

            ProcessBuilder pb = new ProcessBuilder(ollamaPath, "serve");
            pb.redirectErrorStream(true);
            pb.directory(null); // run in default directory

            Process process = pb.start();

            // Allow 15 seconds for the server to initialize
            boolean ready = waitForOllamaToBeReady();

            if (ready) {
                log.info("Ollama server successfully started in the background");
                // The process does not terminate on its own — it will run until the PC is shut down
                return true;
            } else {
                process.destroyForcibly();
                log.warn("Ollama failed to start within {} seconds", STARTUP_TIMEOUT_SECONDS);
                return false;
            }

        } catch (IOException e) {
            log.error("Error starting Ollama: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Waits for Ollama to become ready by polling the API.
     * Uses a non-blocking approach suitable for Java 21 Virtual Threads.
     */
    private boolean waitForOllamaToBeReady() {
        long timeoutMs = TimeUnit.SECONDS.toMillis(STARTUP_TIMEOUT_SECONDS);
        long start = System.currentTimeMillis();

        while (System.currentTimeMillis() - start < timeoutMs) {
            if (isOllamaRunning()) {
                return true;
            }

            // Use LockSupport or a standard sleep
            // In Java 21 Virtual Threads, this yields the thread efficiently
            try {
                Thread.sleep(Duration.ofSeconds(1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Restore interrupted status
                return false;
            }
        }
        return false;
    }
}