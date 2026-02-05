package com.example.demo.service;

import com.example.demo.model.AiUpdate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiAnswerService {
    private final ChatModel chatModel;
    private final SimpMessagingTemplate messagingTemplate;

    // Use a thread-safe reference to store the last response
    private final AtomicReference<String> lastAnswer = new AtomicReference<>("Waiting for analysis...");
    private final AtomicReference<Boolean> isAiProcessing = new AtomicReference<>(false);

    public void processSpeechWithAI(String transcript) {
        if (isAiProcessing.get()) return;    // Don't send a new request if the GPU is still thinking

        Thread.ofVirtual().start(() -> {
            if (isAiProcessing.compareAndSet(false, true)) { // Lock
                try {
                    log.info("CPU starting inference for: {}", transcript);
                    // --- TIMER START ---
                    long startTime = System.currentTimeMillis();
                    String systemPrompt = "You are a helpful assistant. Provide a very concise response in Ukrainian:";
                    String response = chatModel.call(systemPrompt + " " + transcript);
                    long duration = System.currentTimeMillis() - startTime;
                    log.info(">> AI Answered in {}ms: [{}]", duration, response);
                    lastAnswer.set(response);
                    messagingTemplate.convertAndSend("/topic/ai-response", new AiUpdate(response));
                } catch (Exception e) {
                    log.error("Ollama AI processing failed: {}", e.getMessage());
                } finally {
                    isAiProcessing.set(false); // Unlock
                }
            }
        });
    }

    public String getLatestAnswer() {
        return lastAnswer.get();
    }

}