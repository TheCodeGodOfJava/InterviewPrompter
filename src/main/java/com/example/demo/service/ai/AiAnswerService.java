package com.example.demo.service.ai;

import com.example.demo.model.AiUpdate;
import com.example.demo.service.ai.llm.LlmProvider;
import jakarta.annotation.PreDestroy; // standard import for Spring Boot 3
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiAnswerService {

    private final LlmProvider llmProvider;
    private final AiContextService aiContextService;
    private final SimpMessagingTemplate messagingTemplate;

    private final AtomicReference<String> lastAnswer = new AtomicReference<>("Waiting for analysis...");

    // Use a SingleThreadExecutor to ensure AI answers questions ONE BY ONE, not all at once.
    private final ExecutorService aiExecutor = Executors.newSingleThreadExecutor();

    public void processSpeechWithAI(String transcript) {
        // Even if 5 sentences come in at once, they will be processed in order.
        aiExecutor.submit(() -> {
            try {
                log.info("Queue processing: {}", transcript);

                // Optional: Check if history is empty or trivial to avoid wasted calls
                if (aiContextService.getHistory().isEmpty()) return;

                long startTime = System.currentTimeMillis();

                // 1. Send FULL context to AI (The AI sees the conversation flow)
                String aiAnswer = llmProvider.generateAnswer(aiContextService.getHistory());

                // 2. Add AI's answer to context so it remembers it for the next turn
                aiContextService.addMessage("assistant", aiAnswer);

                long duration = System.currentTimeMillis() - startTime;
                log.info("AI Answered in {}ms", duration);

                // 3. Update State & UI
                lastAnswer.set(aiAnswer);
                messagingTemplate.convertAndSend("/topic/ai-response", new AiUpdate(aiAnswer));

            } catch (Exception e) {
                log.error("AI processing failed", e);
                messagingTemplate.convertAndSend("/topic/ai-response", new AiUpdate("Error: " + e.getMessage()));
            }
        });
    }

    public String getLatestAnswer() {
        return lastAnswer.get();
    }

    /**
     * CLEANUP: Stops the thread pool when the server stops.
     * Without this, your JVM might hang on shutdown.
     */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down AI Executor...");
        aiExecutor.shutdown(); // Stop accepting new tasks
        try {
            if (!aiExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                aiExecutor.shutdownNow(); // Force interrupt running tasks
            }
        } catch (InterruptedException e) {
            aiExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}