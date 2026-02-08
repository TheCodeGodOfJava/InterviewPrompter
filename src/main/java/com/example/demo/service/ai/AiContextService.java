package com.example.demo.service.ai;

import com.example.demo.model.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiContextService {

    private final SimpMessagingTemplate messagingTemplate;

    // Thread-safe storage for the conversation
    private final CopyOnWriteArrayList<ChatMessage> conversationHistory = new CopyOnWriteArrayList<>();

    // Maximum number of messages to keep (System + 10 pairs of Q&A)
    private static final int MAX_HISTORY_SIZE = 21;

    // The Persona
    private static final String SYSTEM_PROMPT = """
                Ти - Senior Java Tech Lead. Твоя мета - допомогти кандидату пройти технічну співбесіду на такий же рівень.
                Не давай сухих визначень з Вікіпедії. Давай глибокі, професійні відповіді по питанням з тем\s
                Java, Spring Boot, Hibernate, Object Oriented Programming, Angular, JavaScript та інші.
               \s
                Структура відповіді:
                1. Коротко: що це (суть).
                2. Підводні камені (Best Practices vs Bad Practices).
                3. Як краще робити в Modern Java (Java 17/21), Modern Angular, Modern SQL та інших мовах програмування.
               \s
                Обов'язково згадуй нюанси (наприклад: Cloneable - це broken interface, краще copy constructor).
                Відповідай українською мовою. Будь лаконічним, але технічно точним.
               \s""";

    // Initialize with System Prompt
    public void init() {
        if (conversationHistory.isEmpty()) {
            conversationHistory.add(new ChatMessage("system", SYSTEM_PROMPT));
        }
    }

    /**
     * Adds a message and broadcasts the update to the UI.
     */
    public void addMessage(String role, String content) {
        if (content == null || content.isBlank()) return;

        // Ensure initialized
        if (conversationHistory.isEmpty()) init();

        conversationHistory.add(new ChatMessage(role, content.trim()));

        // Trim history if it gets too big (Sliding Window)
        trimHistory();

        // Broadcast to Frontend (so you see the chat log)
        broadcastUpdate();
    }

    /**
     * Returns a COPY of the history for the AI Provider to use.
     */
    public List<ChatMessage> getHistory() {
        if (conversationHistory.isEmpty()) init();
        return List.copyOf(conversationHistory);
    }

    public void clear() {
        conversationHistory.clear();
        init(); // Restore system prompt
        broadcastUpdate();
        log.info("Context cleared.");
    }

    private void trimHistory() {
        // Keep the System Prompt (index 0) always!
        // Remove the oldest message at index 1 until size is okay.
        while (conversationHistory.size() > MAX_HISTORY_SIZE) {
            conversationHistory.remove(1);
        }
    }

    private void broadcastUpdate() {
        // Send the full list to the frontend topic "/topic/context"
        messagingTemplate.convertAndSend("/topic/context", conversationHistory);
    }
}