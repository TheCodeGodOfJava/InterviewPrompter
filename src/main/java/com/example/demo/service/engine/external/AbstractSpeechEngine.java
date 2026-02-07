package com.example.demo.service.engine.external;

import com.example.demo.service.engine.SpeechRecognizerEngine;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The "Brain" of the operation.
 * It handles Voice Activity Detection (VAD), audio buffering, and WAV file creation.
 * It does NOT know how to transcribe - it delegates that to subclasses via the template method.
 */
@Slf4j
public abstract class AbstractSpeechEngine implements SpeechRecognizerEngine {

    // Buffer for the current sentence being recorded
    protected final ByteArrayOutputStream audioBuffer = new ByteArrayOutputStream();

    // VAD (Voice Activity Detection) State variables
    protected boolean isCollectingSpeech = false;
    protected long lastVoiceActivityTime = System.currentTimeMillis();
    protected final AtomicBoolean isClosed = new AtomicBoolean(false);

    // Configuration
    protected int silenceThreshold = 250; // Default threshold
    private static final int PAUSE_BEFORE_SEND_MS = 600; // Natural conversational pause
    private static final int MIN_PHRASE_DURATION_MS = 800; // Ignore clicks/pops (Anti-Hallucination)

    private long lastRmsLogTime = 0;

    /**
     * @param wavData The complete WAV file (header + pcm) ready to be sent to an API or Container.
     * @return A Future containing the transcribed text.
     */
    protected abstract CompletableFuture<String> transcribe(byte[] wavData);

    @Override
    public void setSilenceThreshold(int threshold) {
        this.silenceThreshold = threshold;
    }

    @Override
    public void processAudio(byte[] data, int read, Consumer<String> onResult) {
        // 1. Safety Check
        if (isClosed.get()) return;

        // 2. Math: Calculate Volume (RMS)
        double currentRms = calculateRMS(data, read);
        long now = System.currentTimeMillis();

        // Optional: Calibration Logging (Prints once per second)
        if (now - lastRmsLogTime > 1000) {
            String status = currentRms > silenceThreshold ? "SPEAKING" : "SILENCE";
            log.info("[Audio Calibration] RMS: {} | Threshold: {} | Status: {}",
                    (int) currentRms, silenceThreshold, status);
            lastRmsLogTime = now;
        }

        if (currentRms > silenceThreshold) {
            // --- STATE: SPEAKING ---
            lastVoiceActivityTime = now;

            if (!isCollectingSpeech) {
                log.debug(">> Speech Detected (RMS: {})", (int) currentRms);
                isCollectingSpeech = true;
            }

            // Record this chunk
            audioBuffer.write(data, 0, read);

        } else {
            // --- STATE: SILENCE ---
            if (isCollectingSpeech) {
                // We are in the "Pause" phase.
                // We keep recording briefly to capture the "tail" of the last word.
                audioBuffer.write(data, 0, read);

                // Check: Has the silence lasted long enough to confirm the sentence is over?
                if (now - lastVoiceActivityTime > PAUSE_BEFORE_SEND_MS) {
                    finalizeAndSend(onResult);
                }
            }
        }
    }

    /**
     * Called when silence is detected.
     * Wraps the raw audio into a WAV container and delegates to the concrete engine.
     */
    private void finalizeAndSend(Consumer<String> onResult) {
        byte[] rawPcm = audioBuffer.toByteArray();

        // Reset immediately so we are ready for the next sentence
        audioBuffer.reset();
        isCollectingSpeech = false;

        // Duration Check (Anti-Hallucination)
        // If the audio is too short (e.g., a cough or chair squeak), ignore it.
        double durationMs = (rawPcm.length / 32000.0) * 1000;
        if (durationMs < MIN_PHRASE_DURATION_MS) {
            log.debug("Dropped short noise ({}ms)", (int) durationMs);
            return;
        }

        log.info("Sentence captured ({}ms). Transcribing...", (int) durationMs);

        // Convert raw PCM to WAV (Required by almost all APIs and Docker models)
        byte[] wavData = addWavHeader(rawPcm);

        // --- DELEGATION STEP ---
        transcribe(wavData).thenAccept(text -> {
            if (text != null && !text.isBlank()) {
                onResult.accept(text);
            }
        });
    }

    /**
     * Helper: Adds the standard 44-byte WAV header to raw PCM data.
     */
    protected byte[] addWavHeader(byte[] pcmAudio) {
        byte[] wavFile = new byte[44 + pcmAudio.length];
        ByteBuffer out = ByteBuffer.wrap(wavFile).order(ByteOrder.LITTLE_ENDIAN);

        out.put(new byte[]{'R', 'I', 'F', 'F'});
        out.putInt(36 + pcmAudio.length);
        out.put(new byte[]{'W', 'A', 'V', 'E'});
        out.put(new byte[]{'f', 'm', 't', ' '});
        out.putInt(16).putShort((short) 1).putShort((short) 1);
        out.putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort((short) 2).putShort((short) 16);
        out.put(new byte[]{'d', 'a', 't', 'a'}).putInt(pcmAudio.length);

        System.arraycopy(pcmAudio, 0, wavFile, 44, pcmAudio.length);
        return wavFile;
    }

    /**
     * Helper: Calculates Root Mean Square (Volume) of 16-bit audio.
     */
    private double calculateRMS(byte[] rawData, int read) {
        long sum = 0;
        for (int i = 0; i < read; i += 2) {
            if (i + 1 >= read) break;
            // Little Endian conversion
            short sample = (short) ((rawData[i] & 0xFF) | (rawData[i + 1] << 8));
            sum += sample * sample;
        }
        int sampleCount = read / 2;
        return (sampleCount == 0) ? 0 : Math.sqrt(sum / (double) sampleCount);
    }

    protected void closeAudioBuffer() {
        try {
            audioBuffer.close();
        } catch (Exception e) {
            log.info("Failed to close audio buffer!");
        }
    }

    /**
     * SHARED HELPER: Returns a Builder with the URI set.
     * Subclasses can then call .GET(), .POST(), or .header() on it.
     */
    protected HttpRequest.Builder newRequest(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url));
    }

    /**
     * SHARED HELPER: Builds a Multipart body for HTTP requests.
     * Use this for both Docker and Remote APIs.
     *
     * @param wavData  The audio file bytes (already with WAV header)
     * @param boundary The multipart boundary string
     * @param metadata A map of text fields (e.g., "model" -> "whisper", "temperature" -> "0")
     * @return The complete byte array ready for HTTP POST
     */
    protected byte[] buildMultipartBody(byte[] wavData, String boundary, Map<String, String> metadata) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] newline = "\r\n".getBytes(StandardCharsets.UTF_8);
            String boundaryLine = "--" + boundary + "\r\n";
            String endBoundary = "--" + boundary + "--\r\n";

            // 1. Write the Audio File Part (Common to all engines)
            output.write(boundaryLine.getBytes(StandardCharsets.UTF_8));
            output.write(("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n").getBytes(StandardCharsets.UTF_8));
            output.write("Content-Type: audio/wav\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            output.write(wavData);
            output.write(newline);

            // 2. Write Metadata Parts (Dynamic based on the engine)
            if (metadata != null) {
                for (Map.Entry<String, String> entry : metadata.entrySet()) {
                    output.write(boundaryLine.getBytes(StandardCharsets.UTF_8));
                    String header = "Content-Disposition: form-data; name=\"" + entry.getKey() + "\"\r\n\r\n";
                    output.write(header.getBytes(StandardCharsets.UTF_8));
                    output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                    output.write(newline);
                }
            }

            // 3. Write Footer
            output.write(endBoundary.getBytes(StandardCharsets.UTF_8));
            return output.toByteArray();

        } catch (IOException e) {
            throw new RuntimeException("Failed to build multipart body", e);
        }
    }

    @Override
    public void close() {
        if (this.isClosed.get()) return;
        this.closeAudioBuffer();
        isClosed.set(true);
    }
}