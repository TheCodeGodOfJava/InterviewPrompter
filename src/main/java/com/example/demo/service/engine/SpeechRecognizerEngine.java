package com.example.demo.service.engine;

public interface SpeechRecognizerEngine {

    int SAMPLE_RATE = 16_000;

    String processAudio(byte[] buffer, int bytesRead);

    void close();
}