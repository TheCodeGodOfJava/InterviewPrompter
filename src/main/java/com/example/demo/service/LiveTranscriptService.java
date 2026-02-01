package com.example.demo.service;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@RequiredArgsConstructor
@Service
public class LiveTranscriptService {

    // Thread-safe, copy-on-write list of individual words
    private final CopyOnWriteArrayList<String> words = new CopyOnWriteArrayList<>();

    private final SimpMessagingTemplate simpMessagingTemplate;


    /**
     * Called from recognition thread when a new final word/phrase arrives
     */
    public void appendWord(String word) {
        if (word == null || word.isBlank()) return;

        String trimmed = word.trim();
        words.add(trimmed);                     // thread-safe append

        broadcastCurrentState();
    }

    /**
     * Get current full text (for initial load or fallback)
     */
    public String getCurrentText() {
        return String.join(" ", words);
    }

    /**
     * Get the word list with indexes (useful for debugging or precise editing)
     */
    public List<WordEntry> getCurrentWords() {
        List<WordEntry> list = new ArrayList<>();
        for (int i = 0; i < words.size(); i++) {
            list.add(new WordEntry(i, words.get(i)));
        }
        return list;
    }

    private void broadcastCurrentState() {
        // Send full current text + version or word list
        // Option A: just the joined string (simplest for frontend)
        String fullText = getCurrentText();
        long version = System.currentTimeMillis(); // or AtomicLong counter

        simpMessagingTemplate.convertAndSend("/topic/transcript",
                new TranscriptUpdate(fullText, version, getCurrentWords()));
    }

    // DTOs
    public record WordEntry(int index, String word) {}
    public record TranscriptUpdate(String fullText, long version, List<WordEntry> words) {}
}
