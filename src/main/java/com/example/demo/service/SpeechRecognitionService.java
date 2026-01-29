package com.example.demo.service;

import org.springframework.stereotype.Service;
import org.vosk.Model;
import org.vosk.Recognizer;

import javax.sound.sampled.*;

@Service
public class SpeechRecognitionService {

    private static final int SAMPLE_RATE = 16000;

    private final Recognizer ukRecognizer;
    private final Recognizer enRecognizer;
    private TargetDataLine line;
    private Thread recognitionThread;

    public SpeechRecognitionService(ModelManagerService modelManagerService) throws Exception {
        modelManagerService.checkAndDownloadModels();
        // Load full Ukrainian model
        Model ukModel = new Model("sound/vosk-model-uk-v3");
        ukRecognizer = new Recognizer(ukModel, SAMPLE_RATE);
        // Load full English model
        Model enModel = new Model("sound/vosk-model-en-us-0.22");
        enRecognizer = new Recognizer(enModel, SAMPLE_RATE);
    }

    public void init() throws Exception {

        // Find Voicemeeter mixer
        Mixer.Info selectedMixer = null;
        for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
            if (mixerInfo.getName().toLowerCase().contains("voicemeeter")) {
                selectedMixer = mixerInfo;
                break;
            }
        }
        if (selectedMixer == null) {
            throw new RuntimeException("Voicemeeter mixer not found!");
        }

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
                e.printStackTrace();
            }
        });
        recognitionThread.start();
    }

    /**
     * Process audio buffer with Ukrainian first, fallback to English
     */
    private String processBuffer(byte[] buffer, int bytesRead) {
        String ukResult = ukRecognizer.acceptWaveForm(buffer, bytesRead) ?
                ukRecognizer.getResult() : ukRecognizer.getPartialResult();

        // Check if result is empty or very short → try English
        if (ukResult == null || ukResult.trim().length() < 2) {
            String enResult = enRecognizer.acceptWaveForm(buffer, bytesRead) ?
                    enRecognizer.getResult() : enRecognizer.getPartialResult();
            return enResult != null ? enResult : "";
        }

        return ukResult;
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
