package com.example.demo.service.ai;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class HallucinationFilterService {

    private final Set<String> hallucinations = ConcurrentHashMap.newKeySet();

    @Value("${filter.hallucinations.file:hallucinations.txt}")
    private String filePath;

    @PostConstruct
    public void init() {
        Path path = Paths.get(filePath);
        if (Files.exists(path)) {
            try {
                // ИСПРАВЛЕНИЕ 1: Жестко задаем UTF-8 для кириллицы
                List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                for (String line : lines) {
                    if (!line.isBlank()) {
                        hallucinations.add(normalize(line));
                    }
                }
                log.info("Loaded {} hallucinations from {}", hallucinations.size(), filePath);
            } catch (IOException e) {
                log.error("Failed to read hallucinations file: {}", filePath, e);
            }
        } else {
            log.info("Hallucinations file not found at {}!", filePath);
        }
    }

    public boolean isValidTranscript(String text) {
        if (text == null || text.isBlank()) return false;

        String normalized = normalize(text);
        if (normalized.isBlank()) return false;

        if (hallucinations.contains(normalized)) {
            log.debug("Blocked hallucination: {}", text);
            return false;
        }

        return true;
    }

    private String normalize(String text) {
        return text.replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase();
    }
}