package com.example.demo.service.ai;

import static com.example.demo.model.ROLE.SYSTEM;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import com.example.demo.model.ChatMessage;
import com.example.demo.model.ROLE;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
               Давай глибокі, професійні відповіді по
               Java, Spring Boot, Hibernate, Object Oriented Programming, Angular, JavaScript та інші.

               Обов'язково згадуй нюанси (наприклад: Cloneable - це broken interface, краще copy constructor).
               Відповідай українською мовою. Будь технічно точним.
               CRITICAL LANGUAGE AND TERMINOLOGY RULES:
            1. The user will communicate with you in Ukrainian.
            2. You must respond with natural, professional Ukrainian for all general explanations and conversational text.
            3. ABSOLUTE PROHIBITION: You must NEVER translate technical terminology, programming concepts, or framework features out of English.
            4. All SQL concepts (e.g., Table-Valued Functions, TRY/CATCH, JOINs), Java/Spring components (e.g., Beans, SecurityFilterChain, @Bean), Angular directives, and architectural patterns MUST remain in pure, untranslated English.
            5. Do not attempt to transliterate English terms into Cyrillic (e.g., write "ThreadLocal", never "ТредЛокал").
            6. Do not map technical terms to Asian characters under any circumstances (e.g., never use "表" for Table).

            EXAMPLE OF CORRECT BEHAVIOR:
            "У T-SQL функція не може мати TRY/CATCH з THROW, тому краще використовувати Table-Valued Function плюс процедуру-обгортку."
              """;

    public void init() {
        if (conversationHistory.isEmpty()) {
            conversationHistory.add(new ChatMessage(SYSTEM, SYSTEM_PROMPT));
        }
    }

    public void addMessage(ROLE role, String content) {
        if (content == null || content.isBlank())
            return;
        if (conversationHistory.isEmpty())
            init();
        conversationHistory.add(new ChatMessage(role, content.trim()));
        trimHistory();
        broadcastUpdate();
    }

    public List<ChatMessage> getHistory() {
        if (conversationHistory.isEmpty())
            init();
        return List.copyOf(conversationHistory);
    }

    private void trimHistory() {
        while (conversationHistory.size() > MAX_HISTORY_SIZE) {
            conversationHistory.remove(1);
        }
    }

    public void clearAnsweredQuestions() {
        if (conversationHistory.size() <= 1)
            return;

        int lastAnswerIndex = -1;
        for (int i = conversationHistory.size() - 1; i > 0; i--) {
            if (ROLE.ASSISTANT.equals(conversationHistory.get(i).role())) {
                lastAnswerIndex = i;
                break;
            }
        }

        if (lastAnswerIndex != -1) {
            retainMessagesAfterIndex(lastAnswerIndex);
            log.info("Context cleared. Retained system prompt and unanswered messages.");
        } else {
            log.info("No answered questions found to clear. Context remains unchanged.");
        }
    }

    public void deleteUpToMessage(String messageId) {
        int targetIndex = -1;
        for (int i = 0; i < conversationHistory.size(); i++) {
            if (conversationHistory.get(i).id().equals(messageId)) {
                targetIndex = i;
                break;
            }
        }

        if (targetIndex > 0) {
            retainMessagesAfterIndex(targetIndex);
            log.info("Deleted history up to message ID: {}", messageId);
            logRemainingHistory();
        }
    }

    private void retainMessagesAfterIndex(int targetIndex) {
        List<ChatMessage> retainedMessages = new ArrayList<>();

        retainedMessages.add(conversationHistory.get(0));

        if (targetIndex + 1 < conversationHistory.size()) {
            retainedMessages.addAll(conversationHistory.subList(targetIndex + 1, conversationHistory.size()));
        }

        conversationHistory.clear();
        conversationHistory.addAll(retainedMessages);
        broadcastUpdate();
    }

    public void deleteSingleMessage(String messageId) {
        boolean removed = conversationHistory
                .removeIf(msg -> !ROLE.SYSTEM.equals(msg.role()) && msg.id().equals(messageId));

        if (removed) {
            log.info("Deleted single message: {}", messageId);
            logRemainingHistory();
            broadcastUpdate();
        }
    }

    private void logRemainingHistory() {
        log.info("=== CURRENT CONTEXT ({} messages) ===", conversationHistory.size());
        for (int i = 0; i < conversationHistory.size(); i++) {
            ChatMessage m = conversationHistory.get(i);

            String preview = m.content().replace("\n", " ");

            if (preview.length() > 80) {
                preview = preview.substring(0, 80) + "...";
            }

            log.info("[{}] {} : {}", i, m.role(), preview);
        }
        log.info("=======================================");
    }

    public void clearAllHistory() {
        conversationHistory.clear();
        init(); 
        broadcastUpdate();
        log.info("Total history wipe completed.");
    }

    private void broadcastUpdate() {
        messagingTemplate.convertAndSend("/topic/context", conversationHistory);
    }
}