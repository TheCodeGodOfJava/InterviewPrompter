package com.example.demo.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.service.ButtonListenerService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/capture")
@RequiredArgsConstructor
public class CaptureMonitorController {

    private final ButtonListenerService buttonListenerService;

    @PostMapping("/monitor")
    public ResponseEntity<String> switchMonitor(@RequestBody Map<String, String> request) {
        String monitorStr = request.get("monitor");
        try {
            ButtonListenerService.TargetMonitor newMonitor = ButtonListenerService.TargetMonitor
                    .valueOf(monitorStr.toUpperCase());
            buttonListenerService.setTargetMonitor(newMonitor);
            return ResponseEntity.ok(newMonitor.name());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Invalid monitor: " + monitorStr);
        }
    }

    @GetMapping("/monitor")
    public ResponseEntity<String> getCurrentMonitor() {
        return ResponseEntity.ok(buttonListenerService.getTargetMonitor().name());
    }
}
