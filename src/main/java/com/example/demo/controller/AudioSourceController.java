package com.example.demo.controller;

import java.util.Arrays;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.model.SOURCE;
import com.example.demo.service.SpeechRecognitionService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/audio")
@Slf4j
@RequiredArgsConstructor // if using Lombok, or use constructor injection
public class AudioSourceController {

    private final SpeechRecognitionService recognitionService;

    @PostMapping("/switch-source")
    public ResponseEntity<String> switchSource(@RequestBody Map<String, String> request) {
        String sourceStr = request.get("source");
        if (sourceStr == null || sourceStr.trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Missing or empty 'source' field");
        }

        try {
            SOURCE newSource = SOURCE.valueOf(sourceStr.trim().toUpperCase());
            recognitionService.switchAudioSource(newSource);
            return ResponseEntity.ok("Successfully switched to " + newSource.name());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body("Invalid source: " + sourceStr + ". Valid values: " +
                            Arrays.toString(SOURCE.values()));
        } catch (Exception e) {
            log.error("Failed to switch to source '{}'", sourceStr, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to switch source: " + e.getMessage());
        }
    }

    @GetMapping("/current-source")
    public ResponseEntity<String> getCurrentSource() {
        return ResponseEntity.ok(recognitionService.getSource().name());
    }

    @PostMapping("/toggle-recognition")
    public ResponseEntity<Boolean> toggleRecognition(@RequestParam boolean enabled) {
        try {
            if (enabled) {
                recognitionService.startRecognition();
            } else {
                recognitionService.shutdownSource();
            }
            return ResponseEntity.ok(recognitionService.isRunning());
        } catch (Exception e) {
            log.error("Failed to toggle recognition", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/recognition-status")
    public ResponseEntity<Boolean> getRecognitionStatus() {
        return ResponseEntity.ok(recognitionService.isRunning());
    }
}