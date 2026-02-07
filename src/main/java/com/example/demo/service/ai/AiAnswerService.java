package com.example.demo.service.ai;

import com.example.demo.model.AiUpdate;
import com.example.demo.service.ai.llm.LlmProvider; // Import your interface
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiAnswerService {

    // CHANGE 1: Inject the Interface, not the specific ChatModel
    private final LlmProvider llmProvider;

    private final SimpMessagingTemplate messagingTemplate;

    private final AtomicReference<String> lastAnswer = new AtomicReference<>("Waiting for analysis...");
    private final AtomicReference<Boolean> isAiProcessing = new AtomicReference<>(false);

    public void processSpeechWithAI(String transcript) {
        if (isAiProcessing.get()) return;

        Thread.ofVirtual().start(() -> {
            if (isAiProcessing.compareAndSet(false, true)) {
                try {
                    log.info("CPU starting inference for: {}", transcript);
                    long startTime = System.currentTimeMillis();

                    String systemPrompt = "You are a helpful assistant. Provide a very concise response in Ukrainian:";

                    // CHANGE 2: Call the provider interface
                    String response = llmProvider.generateAnswer(systemPrompt, transcript);

                    long duration = System.currentTimeMillis() - startTime;
                    log.info(">> AI Answered in {}ms: [{}]", duration, response);

                    lastAnswer.set(response);
                    messagingTemplate.convertAndSend("/topic/ai-response", new AiUpdate(response));
                } catch (Exception e) {
                    log.error("AI processing failed: {}", e.getMessage());
                } finally {
                    isAiProcessing.set(false);
                }
            }
        });
    }

    public String getLatestAnswer() {
        return lastAnswer.get();
    }
}