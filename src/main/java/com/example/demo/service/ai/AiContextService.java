package com.example.demo.service.ai;

import com.example.demo.model.ChatMessage;
import com.example.demo.model.ROLE;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.example.demo.model.ROLE.ASSISTANT;
import static com.example.demo.model.ROLE.SYSTEM;

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
               Ти - Senior Java та Angular Tech Lead. Твоя мета - допомогти кандидату пройти технічну співбесіду на такий же рівень.
               Не давай сухих визначень з Вікіпедії. Давай глибокі, професійні відповіді по питанням з тем\s
               Java, Spring Boot, Hibernate, Object Oriented Programming, Angular, JavaScript та інші.
              \s
               Структура відповіді:
               1. Коротко: що це (суть).
               2. Підводні камені (Best Practices vs Bad Practices).
               3. Як краще робити в Modern Java (Java 17/21), Modern Angular, Modern SQL та інших мовах програмування.
              \s
               Обов'язково згадуй нюанси (наприклад: Cloneable - це broken interface, краще copy constructor).
               Відповідай українською мовою. Будь лаконічним, але технічно точним.\s
               CRITICAL LANGUAGE AND TERMINOLOGY RULES:
            1. You are an expert Principal Software Engineer. The user will communicate with you in Ukrainian.\s
            2. You must respond with natural, professional Ukrainian for all general explanations and conversational text.
            3. ABSOLUTE PROHIBITION: You must NEVER translate technical terminology, programming concepts, or framework features out of English.\s
            4. All SQL concepts (e.g., Table-Valued Functions, TRY/CATCH, JOINs), Java/Spring components (e.g., Beans, SecurityFilterChain, @Bean), Angular directives, and architectural patterns MUST remain in pure, untranslated English.
            5. Do not attempt to transliterate English terms into Cyrillic (e.g., write "ThreadLocal", never "ТредЛокал").
            6. Do not map technical terms to Asian characters under any circumstances (e.g., never use "表" for Table).
            
            EXAMPLE OF CORRECT BEHAVIOR:
            "У T-SQL функція не може мати TRY/CATCH з THROW, тому краще використовувати Table-Valued Function плюс процедуру-обгортку."
              \s""";


    public void init() {
        if (conversationHistory.isEmpty()) {
            conversationHistory.add(new ChatMessage(SYSTEM, SYSTEM_PROMPT));
        }
    }

    /**
     * Adds a message and broadcasts the update to the UI.
     */
    public void addMessage(ROLE role, String content) {
        if (content == null || content.isBlank()) return;
        if (conversationHistory.isEmpty()) init();
        conversationHistory.add(new ChatMessage(role, content.trim()));
        trimHistory();
        broadcastUpdate();
    }

    /**
     * Returns a COPY of the history for the AI Provider to use.
     */
    public List<ChatMessage> getHistory() {
        if (conversationHistory.isEmpty()) init();
        return List.copyOf(conversationHistory);
    }

    private void trimHistory() {
        while (conversationHistory.size() > MAX_HISTORY_SIZE) {
            conversationHistory.remove(1);
        }
    }

    /**
     * Removes all question+answer pairs, keeping only the System prompt
     * and any pending unanswered user questions at the end of the history.
     */
    public void clearAnsweredQuestions() {
        if (conversationHistory.size() <= 1) return; // Nothing to clear if it's just the system prompt

        List<ChatMessage> retainedMessages = new ArrayList<>();

        // 1. Always keep the System Prompt (index 0)
        retainedMessages.add(conversationHistory.getFirst());

        // 2. Find the index of the last time the AI answered
        int lastAnswerIndex = -1;
        for (int i = conversationHistory.size() - 1; i > 0; i--) {
            ROLE role = conversationHistory.get(i).role();
            // Check for whatever role your AI uses for its own answers
            if (ASSISTANT.equals(role)) {
                lastAnswerIndex = i;
                break;
            }
        }

        // 3. If we found an answer, everything after it is an "unanswered" question
        if (lastAnswerIndex != -1) {
            for (int i = lastAnswerIndex + 1; i < conversationHistory.size(); i++) {
                retainedMessages.add(conversationHistory.get(i));
            }

            // 4. Safely update the thread-safe list and broadcast
            conversationHistory.clear();
            conversationHistory.addAll(retainedMessages);
            broadcastUpdate();
            log.info("Context cleared. Retained system prompt and {} unanswered messages.",
                    retainedMessages.size() - 1);
        } else {
            log.info("No answered questions found to clear. Context remains unchanged.");
        }
    }

    private void broadcastUpdate() {
        messagingTemplate.convertAndSend("/topic/context", conversationHistory);
    }
}