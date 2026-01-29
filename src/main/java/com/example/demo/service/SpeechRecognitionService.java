package com.example.demo.service;

import com.example.demo.model.VoskResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.vosk.Model;
import org.vosk.Recognizer;
import tools.jackson.databind.ObjectMapper;

import javax.sound.sampled.*;
import java.util.Arrays;

@Slf4j
@Service
public class SpeechRecognitionService {

    private final ObjectMapper objectMapper;

    private static final int SAMPLE_RATE = 16000;

    private final Recognizer ukRecognizer;
    private final Recognizer enRecognizer;
    private TargetDataLine line;
    private Thread recognitionThread;

    public SpeechRecognitionService(ModelManagerService modelManagerService, ObjectMapper objectMapper) throws Exception {
        this.objectMapper = objectMapper;
        modelManagerService.checkAndDownloadModels();
        // Load full Ukrainian model
        Model ukModel = new Model("sound/vosk-model-uk-v3");
        ukRecognizer = new Recognizer(ukModel, SAMPLE_RATE);
        // Load full English model
        Model enModel = new Model("sound/vosk-model-en-us-0.22");
        enRecognizer = new Recognizer(enModel, SAMPLE_RATE);
    }

    public void initRecognition() throws Exception {
        // Find Voicemeeter mixer
        Mixer.Info selectedMixer = Arrays.stream(AudioSystem.getMixerInfo())
                .filter(mixer -> mixer.getName() != null)
                .filter(mixer -> mixer.getName().toLowerCase().contains("voicemeeter"))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Voicemeeter mixer not found"));

        // Setup audio line
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
        line = (TargetDataLine) AudioSystem.getMixer(selectedMixer).getLine(info);
        line.open(format);
        line.start();
    }

    public void startRecognition() {

        recognitionThread = new Thread(() -> {
            byte[] buffer = new byte[4096];
            System.out.println("Recognition started...");

            try {
                while (!Thread.currentThread().isInterrupted()) {
                    int n = line.read(buffer, 0, buffer.length);
                    if (n > 0) {
                        String result = processBuffer(buffer, n);
                        if (!result.isEmpty()) {
                            System.out.println(result);
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Error during speech recognition", e);
            }
        });
        recognitionThread.start();
    }

    /**
     * Process audio buffer with Ukrainian first, fallback to English
     */
    private String processBuffer(byte[] buffer, int bytesRead) {

        boolean ukFinal = ukRecognizer.acceptWaveForm(buffer, bytesRead);
        String ukJson = ukFinal
                ? ukRecognizer.getResult()
                : ukRecognizer.getPartialResult();

        String ukText = extractText(ukJson);

        if (ukText.isBlank()) {
            boolean enFinal = enRecognizer.acceptWaveForm(buffer, bytesRead);
            String enJson = enFinal
                    ? enRecognizer.getResult()
                    : enRecognizer.getPartialResult();

            return extractText(enJson);
        }

        return ukText;
    }

    private String extractText(String json) {
        if (json == null || json.isBlank()) {
            return "";
        }

        try {
            VoskResult result = objectMapper.readValue(json, VoskResult.class);

            if (result.text() != null && !result.text().isBlank()) {
                return result.text().trim();
            }

            if (result.partial() != null) {
                return result.partial().trim();
            }

        } catch (Exception e) {
            log.warn("Failed to parse Vosk JSON: {}", json, e);
        }

        return "";
    }


    public void stopRecognition() {
        if (recognitionThread != null && recognitionThread.isAlive()) {
            recognitionThread.interrupt();
        }
        if (line != null) {
            line.stop();
            line.close();
        }
        System.out.println("Recognition stopped.");
    }
}
