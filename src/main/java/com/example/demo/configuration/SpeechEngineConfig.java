package com.example.demo.configuration;

import com.example.demo.service.ModelManagerService;
import com.example.demo.service.engine.SpeechRecognizerEngine;
import com.example.demo.service.engine.VoskEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration
public class SpeechEngineConfig {

    @Bean
    public SpeechRecognizerEngine voskEngine(ModelManagerService modelManager, ObjectMapper mapper) throws IOException {
        return new VoskEngine(modelManager, mapper);
    }
}