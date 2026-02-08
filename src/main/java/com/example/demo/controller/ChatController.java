package com.example.demo.controller;

import com.example.demo.model.ChatMessage;
import com.example.demo.service.ai.AiContextService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final AiContextService aiContextService;

    // HTTP GET: Initial load of history
    @GetMapping("/history")
    public List<ChatMessage> getChatHistory() {
        return aiContextService.getHistory();
    }

    // WebSocket Subscribe: Send history immediately on connection
    @SubscribeMapping("/context")
    public List<ChatMessage> onSubscribe() {
        return aiContextService.getHistory();
    }
}