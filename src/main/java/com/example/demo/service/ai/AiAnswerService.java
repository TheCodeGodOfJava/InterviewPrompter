package com.example.demo.service.ai;

import com.example.demo.model.AiUpdate;
import com.example.demo.model.ChatMessage;
import com.example.demo.model.ROLE;
import com.example.demo.service.ai.llm.LlmProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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

    private final ChatModel chatModel;

    private final AiContextService aiContextService;
    private final SimpMessagingTemplate messagingTemplate;

    private final AtomicReference<String> lastAnswer = new AtomicReference<>("");
    private final AtomicReference<String> lastProcessedUserInput = new AtomicReference<>("");
    private final ExecutorService aiExecutor = Executors.newSingleThreadExecutor();

    public String getLatestAnswer() {
        return lastAnswer.get();
    }

    public void generateManualAnswer() {
        aiExecutor.submit(() -> {
            try {
                log.info("Text analysis requested...");

                List<ChatMessage> history = aiContextService.getHistory();
                if (history.size() <= 1) {
                    sendUnlockMessage("The context is empty...");
                    return;
                }

                String currentUserState = history.stream()
                        .filter(msg -> ROLE.USER.equals(msg.role()))
                        .map(ChatMessage::content)
                        .collect(Collectors.joining(" "));

                if (currentUserState.equals(lastProcessedUserInput.get())) {
                    sendUnlockMessage(lastAnswer.get());
                    return;
                }

                messagingTemplate.convertAndSend("/topic/ai-response", new AiUpdate("", "THINKING"));

                String aiAnswer = llmProvider.generateAnswer(history);

                aiContextService.addMessage(ROLE.ASSISTANT, aiAnswer);
                lastProcessedUserInput.set(currentUserState);
                lastAnswer.set(aiAnswer);
                sendUnlockMessage(aiAnswer);

            } catch (Exception e) {
                log.error("Text processing failed", e);
                messagingTemplate.convertAndSend("/topic/ai-response", new AiUpdate("Error", "ERROR"));
            }
        });
    }

    public void processScreenshot(byte[] imageBytes) {
        aiExecutor.submit(() -> {
            try {
                if (imageBytes == null)
                    return;

                String base64Image = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(imageBytes);
                messagingTemplate.convertAndSend("/topic/screen-analysis", new ChatMessage(ROLE.USER, base64Image));

                List<org.springframework.ai.chat.messages.Message> springAiMessages = new ArrayList<>();
                for (ChatMessage msg : aiContextService.getHistory()) {
                    switch (msg.role()) {
                        case SYSTEM -> springAiMessages.add(new SystemMessage(msg.content()));
                        case USER -> springAiMessages.add(new UserMessage(msg.content()));
                        case ASSISTANT -> springAiMessages.add(new AssistantMessage(msg.content()));
                    }
                }

                Media media = new Media(MimeTypeUtils.IMAGE_JPEG, new ByteArrayResource(imageBytes));

                UserMessage multimodalMessage = UserMessage.builder()
                        .text("Аналізуй цей скріншот. Орієнтуйся на контекст нашої розмови.")
                        .media(media)
                        .build();
                springAiMessages.add(multimodalMessage);

                // Starts "Thinking..."
                messagingTemplate.convertAndSend("/topic/ai-response", new AiUpdate("", "THINKING"));
                log.info("Sending request to Gemini API...");
                log.info("Payload size: {} bytes ({} MB)", imageBytes.length, imageBytes.length / (1024 * 1024));
                long startTime = System.currentTimeMillis();

                ChatResponse response = CompletableFuture
                        .supplyAsync(() -> chatModel.call(new Prompt(springAiMessages)))
                        .get(30, TimeUnit.SECONDS);

                long duration = System.currentTimeMillis() - startTime;
                log.info("The screenshot analysis completed in {}ms", duration);

                String answer = response.getResult().getOutput().getText();
                messagingTemplate.convertAndSend("/topic/screen-analysis", new ChatMessage(ROLE.ASSISTANT, answer));

                // Unlocks UI on success
                sendUnlockMessage(answer);

            } catch (Exception e) {
                log.error("Gemini screenshot analysis failed", e);
                messagingTemplate.convertAndSend("/topic/screen-analysis",
                        new ChatMessage(ROLE.ASSISTANT, "⚠️ Error analysing image!"));

                sendUnlockMessage("⚠️ Error analysing image: " + e.getMessage());
            }
        });
    }

    public void resetState() {
        lastAnswer.set("");
        lastProcessedUserInput.set("");
        messagingTemplate.convertAndSend("/topic/ai-response", new AiUpdate("", "READY"));
        log.info("AI Service state reset.");
    }

    private void sendUnlockMessage(String text) {
        messagingTemplate.convertAndSend("/topic/ai-response", new AiUpdate(text, "READY"));
    }
}
