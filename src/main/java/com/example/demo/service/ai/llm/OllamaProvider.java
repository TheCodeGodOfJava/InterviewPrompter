package com.example.demo.service.ai.llm;

import com.example.demo.model.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ai.provider", havingValue = "ollama", matchIfMissing = true)
public class OllamaProvider implements LlmProvider, SmartInitializingSingleton {

    private final ChatModel chatModel;

    private static final String OLLAMA_API_URL = "http://localhost:11434/api/tags";
    private static final long STARTUP_TIMEOUT_SECONDS = 15;

    // --- STRATEGY IMPLEMENTATION ---
    @Override
    public String generateAnswer(List<ChatMessage> history) {
        log.debug("Ollama generating answer...");
        // Ideally, you use the 'messages' endpoint of Ollama.
        // But if you use the simple 'generate' endpoint, you manually combine them:

        StringBuilder promptBuilder = new StringBuilder();
        for (ChatMessage msg : history) {
            promptBuilder.append(msg.role().toUpperCase()).append(": ")
                    .append(msg.content()).append("\n");
        }

        // Call Ollama with the huge string
        return chatModel.call(promptBuilder.toString());
    }

    private void initializeOllama() {
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
            conn.setConnectTimeout(1000); // Shorter timeout for quick checks

            return conn.getResponseCode() == 200;
        } catch (IOException e) {
            log.info("Ollama. Failed to connect to the server.");
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

    @Override
    public void afterSingletonsInstantiated() {
        initializeOllama();
    }

}