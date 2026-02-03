package com.example.demo.configuration;

import com.example.demo.service.ModelManagerService;
import com.example.demo.service.engine.SpeechRecognizerEngine;
import com.example.demo.service.engine.VoskEngine;
import com.example.demo.service.engine.WhisperEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.io.IOException;

@Slf4j
@Configuration
public class SpeechEngineConfig {

    @Bean
    @Primary
    @ConditionalOnProperty(name = "speech.engine", havingValue = "whisper", matchIfMissing = true)
    public SpeechRecognizerEngine whisperEngine(ObjectMapper mapper) {
        log.info("Loading Whisper Engine (CPU)");
        return new WhisperEngine(mapper);
    }

    @Bean
    @ConditionalOnProperty(name = "speech.engine", havingValue = "vosk")
    public SpeechRecognizerEngine voskEngine(ModelManagerService modelManager, ObjectMapper mapper) throws IOException {
        log.info("Loading Vosk Engine (CPU Fallback)");
        return new VoskEngine(modelManager, mapper);
    }
}