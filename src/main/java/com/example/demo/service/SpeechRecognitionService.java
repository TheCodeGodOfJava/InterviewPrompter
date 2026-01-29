package com.example.demo.service;

import org.springframework.stereotype.Service;
import org.vosk.Model;
import org.vosk.Recognizer;

import javax.sound.sampled.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class SpeechRecognitionService {

    private Recognizer ukRecognizer;
    private Recognizer enRecognizer;
    private TargetDataLine line;
    private ExecutorService executor;

    public void init() throws Exception {
        // Загружаем модели один раз
        Model ukModel = new Model("models/vosk-model-uk-v3");        // путь к украинской модели
        Model enModel = new Model("models/vosk-model-en-us-0.15");   // путь к английской модели

        ukRecognizer = new Recognizer(ukModel, 16000);
        enRecognizer = new Recognizer(enModel, 16000);

        // Настройка аудио (микрофон или виртуальный кабель)
        AudioFormat format = new AudioFormat(16000, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

        line = (TargetDataLine) AudioSystem.getLine(info);
        line.open(format);
        line.start();

        executor = Executors.newSingleThreadExecutor();
    }

    public void startRecognition(RecognitionListener listener) {
        executor.submit(() -> {
            byte[] buffer = new byte[4096];
            System.out.println("Recognition started...");

            try {
                while (!Thread.currentThread().isInterrupted()) {
                    int n = line.read(buffer, 0, buffer.length);
                    if (n > 0) {
                        // Сначала пробуем украинскую модель
                        if (ukRecognizer.acceptWaveForm(buffer, n)) {
                            String result = ukRecognizer.getResult();
                            if (!result.contains("\"text\":\"\"")) {
                                listener.onResult("[UK] " + result);
                                continue;
                            }
                        }

                        // Если пусто → пробуем английскую модель
                        if (enRecognizer.acceptWaveForm(buffer, n)) {
                            String result = enRecognizer.getResult();
                            if (!result.contains("\"text\":\"\"")) {
                                listener.onResult("[EN] " + result);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    public void stopRecognition() {
        if (line != null) {
            line.stop();
            line.close();
        }
        if (ukRecognizer != null) ukRecognizer.close();
        if (enRecognizer != null) enRecognizer.close();
        if (executor != null) executor.shutdownNow();
    }

    // Интерфейс для отдачи результата
    public interface RecognitionListener {
        void onResult(String text);
    }
}
