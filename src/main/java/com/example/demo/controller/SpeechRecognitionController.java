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
    public String start(@RequestParam(defaultValue = "ru") String lang) {
        try {
            service.init(lang);
            service.startRecognition();
            return "Распознавание запущено для языка: " + lang;
        } catch (Exception e) {
            e.printStackTrace();
            return "Ошибка: " + e.getMessage();
        }
    }

    @PostMapping("/stop")
    public String stop() {
        service.stopRecognition();
        return "Распознавание остановлено";
    }
}

