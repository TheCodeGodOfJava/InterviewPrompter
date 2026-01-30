package com.example.demo.controller;

import com.example.demo.service.SpeechRecognitionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/speech")
public class SpeechRecognitionController {

    private final SpeechRecognitionService service;

    public SpeechRecognitionController(SpeechRecognitionService service) {
        this.service = service;
    }

    @PostMapping("/start")
    public String start() {
        try {
            service.start();
            return "Speech recognition started (Ukrainian + English).";
        } catch (Exception e) {
            log.warn("Failed to start recognition!", e);
            return "Error starting recognition: " + e.getMessage();
        }
    }

    @PostMapping("/stop")
    public String stop() {
        service.stop();
        return "Speech recognition stopped.";
    }
}
