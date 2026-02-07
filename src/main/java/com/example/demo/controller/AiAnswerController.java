package com.example.demo.controller;

import com.example.demo.model.AiUpdate;
import com.example.demo.service.ai.AiAnswerService;
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
    public AiUpdate getLatest() {
        return new AiUpdate(aiAnswerService.getLatestAnswer());
    }

    /**
     * Sends the latest AI answer immediately upon WebSocket subscription
     */
    @SubscribeMapping("/ai-response")
    public AiUpdate onSubscribe() {
        return getLatest();
    }
}