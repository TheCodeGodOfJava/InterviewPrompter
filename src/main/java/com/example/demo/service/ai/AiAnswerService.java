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
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiAnswerService {

    private final LlmProvider llmProvider;
    private final AiContextService aiContextService;
    private final SimpMessagingTemplate messagingTemplate;

    private final AtomicReference<String> lastAnswer = new AtomicReference<>("");

    private final AtomicReference<String> lastProcessedUserInput = new AtomicReference<>("");

    private final ExecutorService aiExecutor = Executors.newSingleThreadExecutor();

    public void generateManualAnswer() {
        aiExecutor.submit(() -> {
            try {
                log.info("Manual AI analysis requested...");

                if (aiContextService.getHistory().size() <= 1) {
                    log.info("Not enough context to analyze yet.");
                    sendUnlockMessage("Waiting for conversation context...");
                    return;
                }

                String currentUserState = aiContextService.getHistory().stream()
                        .filter(msg -> "user".equals(msg.role()))
                        .map(msg -> msg.content())
                        .collect(Collectors.joining(" "));

                if (currentUserState.trim().isEmpty()) {
                    log.info("AI Request Ignored: User context is empty.");
                    sendUnlockMessage("");
                    return;
                }

                if (currentUserState.equals(lastProcessedUserInput.get())) {
                    log.info("AI Request Ignored: User input hasn't changed. Returning cached answer.");
                    sendUnlockMessage(lastAnswer.get());
                    return;
                }

                lastProcessedUserInput.set(currentUserState);

                long startTime = System.currentTimeMillis();

                String aiAnswer = llmProvider.generateAnswer(aiContextService.getHistory());

                aiContextService.addMessage("assistant", aiAnswer);

                long duration = System.currentTimeMillis() - startTime;
                log.info("AI Answered in {}ms", duration);

                lastAnswer.set(aiAnswer);
                sendUnlockMessage(aiAnswer);

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

    /**
     * Helper method to instantly send a payload to Angular so the UI spinner stops.
     */
    private void sendUnlockMessage(String text) {
        messagingTemplate.convertAndSend("/topic/ai-response", new AiUpdate(text, "READY"));
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