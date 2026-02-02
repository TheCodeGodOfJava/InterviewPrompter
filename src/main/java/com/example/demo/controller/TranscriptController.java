package com.example.demo.controller;

import com.example.demo.model.TranscriptUpdate;
import com.example.demo.model.WordEntry;
import com.example.demo.service.LiveTranscriptService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Controller for accessing the live recognized transcript:
 * - REST endpoint for initial load
 * - STOMP @SubscribeMapping to send current state when client subscribes
 */
@RestController
@RequestMapping("/api/transcript")
@RequiredArgsConstructor
public class TranscriptController {

    private final LiveTranscriptService liveTranscriptService;

    /**
     * HTTP GET – returns current full text and word list with indexes
     * Useful for initial page load in Angular before WebSocket connects
     */
    @GetMapping
    public TranscriptUpdate getCurrentTranscript() {
        String fullText = liveTranscriptService.getCurrentText();
        long version = System.currentTimeMillis(); // or use a proper counter if you add one later
        List<WordEntry> wordsWithIndex = liveTranscriptService.getCurrentWords();

        return new TranscriptUpdate(fullText, version, wordsWithIndex);
    }

    /**
     * STOMP subscription handler
     * When client subscribes to /topic/transcript (or /topic/transcript/{something}),
     * this method is called **once** and its return value is sent **only to that subscriber**
     * as an initial snapshot.
     * <p>
     * Future broadcasts from LiveTranscriptService will go to all subscribers normally.
     */
    @SubscribeMapping("/transcript")
    public TranscriptUpdate onSubscribe() {
        return getCurrentTranscript(); // reuse the logic
    }
}