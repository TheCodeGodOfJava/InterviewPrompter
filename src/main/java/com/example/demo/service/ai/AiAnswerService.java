package com.example.demo.service.ai;

import com.example.demo.model.AiUpdate;
import com.example.demo.service.ai.llm.LlmProvider;
import jakarta.annotation.PreDestroy;
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

    private final AtomicReference<String> lastAnswer = new AtomicReference<>("");

    private final ExecutorService aiExecutor = Executors.newSingleThreadExecutor();

    public void generateManualAnswer() {
        aiExecutor.submit(() -> {
            try {
                log.info("Manual AI analysis requested...");

                // Optional safeguard: Don't query if the history only contains the System Prompt
                if (aiContextService.getHistory().size() <= 1) {
                    log.info("Not enough context to analyze yet.");
                    messagingTemplate.convertAndSend("/topic/ai-response",
                            new AiUpdate("Waiting for conversation context...", "READY"));
                    return;
                }

                long startTime = System.currentTimeMillis();

                // 1. Send FULL context to AI
                String aiAnswer = llmProvider.generateAnswer(aiContextService.getHistory());

                // 2. Add AI's answer to context so it remembers it for the next turn
                aiContextService.addMessage("assistant", aiAnswer);

                long duration = System.currentTimeMillis() - startTime;
                log.info("AI Answered in {}ms", duration);

                // 3. Update State & push success to UI
                lastAnswer.set(aiAnswer);
                messagingTemplate.convertAndSend("/topic/ai-response", new AiUpdate(aiAnswer, "READY"));

            } catch (Exception e) {
                log.error("AI processing failed", e);
                // Push error state to UI so the spinner stops loading
                messagingTemplate.convertAndSend("/topic/ai-response",
                        new AiUpdate("Error generating intelligence: " + e.getMessage(), "ERROR"));
            }
        });
    }

    public String getLatestAnswer() {
        return lastAnswer.get();
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down AI Executor...");
        aiExecutor.shutdown();
        try {
            if (!aiExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                aiExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            aiExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}