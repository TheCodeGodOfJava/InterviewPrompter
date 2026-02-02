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

    public void processSpeechWithAI(String transcript) {
        if (transcript.length() < 10) return;

        Thread.ofVirtual().start(() -> {
            try {
                String systemPrompt = "You are a helpful assistant. Provide a very concise response in Ukrainian to this speech segment:";
                String response = chatModel.call(systemPrompt + " " + transcript);

                // Store it for REST requests
                lastAnswer.set(response);

                // Push it for WebSocket subscribers
                messagingTemplate.convertAndSend("/topic/ai-response", new AiUpdate(response));
            } catch (Exception e) {
                log.error("Ollama AI processing failed", e);
            }
        });
    }

    public String getLatestAnswer() {
        return lastAnswer.get();
    }

}