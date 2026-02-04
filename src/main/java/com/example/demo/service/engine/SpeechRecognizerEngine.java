package com.example.demo.service.engine;

import java.util.function.Consumer;

public interface SpeechRecognizerEngine {

    int SAMPLE_RATE = 16_000;

    void processAudio(byte[] buffer, int bytesRead, Consumer<String> onResult);

    void close();
}