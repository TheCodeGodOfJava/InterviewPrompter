package com.example.demo.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.model.ChatMessage;
import com.example.demo.service.ai.AiAnswerService;
import com.example.demo.service.ai.AiContextService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final AiContextService aiContextService;
    private final AiAnswerService aiAnswerService;

    @GetMapping("/history")
    public List<ChatMessage> getChatHistory() {
        return aiContextService.getHistory();
    }

    @SubscribeMapping("/context")
    public List<ChatMessage> onSubscribe() {
        return aiContextService.getHistory();
    }

    @DeleteMapping("/history/up-to/{id}")
    public ResponseEntity<Void> deleteHistoryUpTo(@PathVariable String id) {
        aiContextService.deleteUpToMessage(id);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/history/single/{id}")
    public ResponseEntity<Void> deleteSingleMessage(@PathVariable String id) {
        aiContextService.deleteSingleMessage(id);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/history/all")
    public ResponseEntity<Void> clearAll() {
        aiContextService.clearAllHistory();
        aiAnswerService.resetState();
        return ResponseEntity.ok().build();
    }
}