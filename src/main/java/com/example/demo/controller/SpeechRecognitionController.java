package com.example.demo.controller;

import com.example.demo.service.SpeechRecognitionService;
import org.springframework.web.bind.annotation.*;

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
            service.init();
            service.startRecognition();
            return "Speech recognition started (Ukrainian + English).";
        } catch (Exception e) {
            e.printStackTrace();
            return "Error starting recognition: " + e.getMessage();
        }
    }

    @PostMapping("/stop")
    public String stop() {
        service.stopRecognition();
        return "Speech recognition stopped.";
    }
}
