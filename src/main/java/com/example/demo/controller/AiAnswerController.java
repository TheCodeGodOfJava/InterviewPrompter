package com.example.demo.controller;

import com.example.demo.service.AiAnswerService;
import com.example.demo.service.AiAnswerService.AIUpdate;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiAnswerController {

    private final AiAnswerService aiAnswerService;

    @GetMapping("/latest")
    public AIUpdate getLatest() {
        return new AIUpdate(aiAnswerService.getLatestAnswer());
    }

    /**
     * Sends the latest AI answer immediately upon WebSocket subscription
     */
    @SubscribeMapping("/ai-response")
    public AIUpdate onSubscribe() {
        return getLatest();
    }
}