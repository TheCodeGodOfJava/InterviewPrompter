package com.example.demo.service;

import org.springframework.stereotype.Service;
import org.vosk.Model;
import org.vosk.Recognizer;

import javax.sound.sampled.*;

@Service
public class SpeechRecognitionService {

    private Model ruModel;
    private Model enModel;
    private Recognizer recognizer;
    private TargetDataLine line;

    public void init(String language) throws Exception {
        // Load a speech model
        if ("ru".equals(language)) {
            ruModel = new Model("models/vosk-model-small-ru-0.22");
            recognizer = new Recognizer(ruModel, 16000);
        } else {
            enModel = new Model("models/vosk-model-small-en-us-0.15");
            recognizer = new Recognizer(enModel, 16000);
        }

        // Find Voicemeeter mixer
        Mixer.Info selectedMixer = null;
        for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
            if (mixerInfo.getName().toLowerCase().contains("voicemeeter")) {
                selectedMixer = mixerInfo;
                break;
            }
        }
        if (selectedMixer == null) {
            throw new RuntimeException("Not found Voicemeeter mixer!");
        }

        AudioFormat format = new AudioFormat(16000, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
        line = (TargetDataLine) AudioSystem.getMixer(selectedMixer).getLine(info);
        line.open(format);
        line.start();
    }

    public void startRecognition() {
        new Thread(() -> {
            byte[] buffer = new byte[4096];
            System.out.println("Starting recognition...");
            try {
                while (true) {
                    int n = line.read(buffer, 0, buffer.length);
                    if (n > 0) {
                        if (recognizer.acceptWaveForm(buffer, n)) {
                            System.out.println(recognizer.getResult());
                        } else {
                            System.out.println(recognizer.getPartialResult());
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    public void stopRecognition() {
        if (line != null) line.stop();
        if (line != null) line.close();
        if (recognizer != null) recognizer.close();
    }
}

