package com.example.demo.service.ai.llm;

import com.example.demo.model.ChatMessage;

import java.util.List;

public interface LlmProvider {
    /**
     * Sends the prompt to the AI and returns the text response.
     * This method is blocking; the caller handles threading.
     */
    String generateAnswer(List<ChatMessage> history);
}