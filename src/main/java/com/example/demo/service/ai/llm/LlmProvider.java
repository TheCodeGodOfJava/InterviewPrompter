package com.example.demo.service.ai.llm;

public interface LlmProvider {
    /**
     * Sends the prompt to the AI and returns the text response.
     * This method is blocking; the caller handles threading.
     */
    String generateAnswer(String systemPrompt, String userPrompt);
}