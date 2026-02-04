package com.example.demo.service.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Slf4j
public class WhisperEngine implements SpeechRecognizerEngine {

    private final ObjectMapper objectMapper;

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final HttpClient client;

    // The volume level (Root Mean Square) required to trigger recording.
    // 400 is a good baseline. Increase to 800 if you have background noise.
    private static final int SILENCE_THRESHOLD_RMS = 400;

    // How long (in ms) we must hear silence before we assume the user stopped speaking.
    // 600ms is a natural conversational pause.
    private static final int PAUSE_BEFORE_SEND_MS = 600;

    // Minimum audio length. If audio is shorter than this (e.g., a keyboard click), ignore it.
    // This is the #1 defense against "Hallucinations".
    private static final int MIN_PHRASE_DURATION_MS = 800;
    private static final String MODEL_NAME = "Systran/faster-whisper-medium";

    private static final String ENGINE_LABEL_KEY = "managed-by";
    private static final String ENGINE_LABEL_VALUE = "interview-prompter-whisper";

    private final DockerClient dockerClient;
    private String containerId;

    // --- NEW: STATE MANAGEMENT ---
    // Replacing the fixed buffer logic with dynamic state
    private final ByteArrayOutputStream audioBuffer = new ByteArrayOutputStream();

    // Is the engine currently capturing a sentence?
    private boolean isCollectingSpeech = false;

    // The timestamp (ms) of the last time we heard a human voice
    private long lastVoiceActivityTime = System.currentTimeMillis();

    // Thread-safe shutdown flag (Replaces 'volatile Boolean engineRunning')
    private final AtomicBoolean isClosed = new AtomicBoolean(false);


    public WhisperEngine(ObjectMapper objectMapper) {
        client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1) // Force HTTP/1.1
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        this.objectMapper = objectMapper;
        this.dockerClient = createDockerClient();
    }

    @PostConstruct
    public void initSync() {
        ensureImageExists();
        this.containerId = startContainer();
        waitForHealth();
        ensureModelDownloaded();
        warmUpModel();
    }

    private void ensureModelDownloaded() {
        log.info("Waiting for model {} to be fully cached and loaded...", MODEL_NAME);

        // Trigger the download once
        triggerDownloadViaApi();

        int attempts = 0;
        while (attempts < 300) { // 300 * 5s = 25 minutes (safe for slow internet)
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:8000/v1/models"))
                        .GET().build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                // We only proceed if our model is present in the list of models.
                if (response.statusCode() == 200 && response.body().contains(MODEL_NAME)) {
                    log.info("SUCCESS: Model {} is verified and ready for inference!", MODEL_NAME);
                    return;
                }

                if (attempts % 6 == 0) { // Every 30 seconds
                    log.info("Still downloading/loading {}... (Check Docker logs for %)", MODEL_NAME);
                    printLatestDockerLogs();
                }

                attempts++;
                Thread.sleep(5000);
            } catch (Exception e) {
                attempts++;
                log.info("Next attempt: {} ", attempts);
            }
        }
        throw new RuntimeException("Model failed to load. Check internet connection or disk space.");
    }

    private void triggerDownloadViaApi() {
        String encoded = URLEncoder.encode(MODEL_NAME, StandardCharsets.UTF_8);
        String url = "http://localhost:8000/v1/models/" + encoded;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(res -> log.info("Model download signal sent. Status: {}", res.statusCode()));
        } catch (Exception e) {
            log.error("Failed to signal download: {}", e.getMessage());
        }
    }

    private void printLatestDockerLogs() {
        try {
            dockerClient.logContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withTail(20) // Increase this to 20 to see past the GET requests
                    .exec(new com.github.dockerjava.api.async.ResultCallback.Adapter<>() {
                        @Override
                        public void onNext(com.github.dockerjava.api.model.Frame item) {
                            String logLine = new String(item.getPayload()).trim();
                            // Ignore the boring HTTP GET logs so we can see the good stuff
                            if (!logLine.contains("GET /v1/models") && !logLine.contains("GET /health")) {
                                log.info("[WHISPER-PROGRESS] {}", logLine);
                            }
                        }
                    }).awaitCompletion(1, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("Could not fetch logs: {}", e.getMessage());
        }
    }

    private DockerClient createDockerClient() {
        log.info("Initializing Docker Client...");

        DefaultDockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                // Force the standard Windows Docker pipe
                .withDockerHost("npipe:////./pipe/docker_engine").build();

        DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder().dockerHost(config.getDockerHost()).sslConfig(config.getSSLConfig()).maxConnections(100).connectionTimeout(Duration.ofSeconds(5)) // Don't wait forever
                .responseTimeout(Duration.ofSeconds(30)).build();

        return DockerClientImpl.getInstance(config, httpClient);
    }

    private void ensureImageExists() {
        String imageName = "ghcr.io/speaches-ai/speaches:latest-cpu";
        log.info("Checking for image: {}", imageName);
        try {
            // Try to inspect the image - if this fails, we need to pull it
            dockerClient.inspectImageCmd(imageName).exec();
            log.info("Image {} found locally.", imageName);
        } catch (NotFoundException e) {
            log.info("Image not found. Pulling {} (This will take a few minutes, it could be several GBs)...", imageName);
            try {
                dockerClient.pullImageCmd(imageName).start().awaitCompletion(); // <--- CRITICAL: Do not continue until download is 100%
                log.info("Pull complete.");
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Image pull interrupted", ie);
            }
        }
    }

    private void waitForHealth() {
        log.info("Waiting for Whisper model to load into RAM...");
        boolean isReady = false;
        int attempts = 0;

        while (!isReady && attempts < 60) {
            try {
                URL url = URI.create("http://localhost:8000/health").toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(1000);
                if (conn.getResponseCode() == 200) {
                    isReady = true;
                }
            } catch (Exception e) {
                log.info("Could not connect to Whisper model");
            } finally {
                attempts++;
            }
            if (!isReady) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Startup interrupted", ie);
                }
            }
        }
        if (!isReady) throw new RuntimeException("Whisper CPU service failed to start!");
        log.info("Whisper is ready on your CPU!");
    }

    @Override
    public void processAudio(byte[] data, int read, Consumer<String> onResult) {
        // 1. Safety Check: Don't process if engine is shutting down
        if (isClosed.get()) return;

        // 2. Analyze Signal Energy
        double currentRms = calculateRMS(data, read);
        long now = System.currentTimeMillis();

        if (currentRms > SILENCE_THRESHOLD_RMS) {
            // --- STATE: SPEAKING ---
            // The user is actively talking.
            lastVoiceActivityTime = now;

            if (!isCollectingSpeech) {
                log.debug(">> Speech Detected (RMS: {})", (int) currentRms);
                isCollectingSpeech = true;
            }

            // Always save data while speaking
            audioBuffer.write(data, 0, read);

        } else {
            // --- STATE: SILENCE ---
            if (isCollectingSpeech) {
                // We were speaking, but now it is quiet.
                // We keep recording briefly to capture the "tail" of the word.
                audioBuffer.write(data, 0, read);

                // Check: Has the silence lasted long enough to mark the sentence as "Finished"?
                long silenceDuration = now - lastVoiceActivityTime;

                if (silenceDuration > PAUSE_BEFORE_SEND_MS) {
                    // Logic Gate: The sentence is officially over.
                    finalizeAndSend(onResult);
                }
            }
        }
    }

    private void finalizeAndSend(Consumer<String> onResult) {
        byte[] payload = audioBuffer.toByteArray();

        // Reset state immediately so we are ready for the next sentence
        audioBuffer.reset();
        isCollectingSpeech = false;

        // --- MATH: Calculate Audio Duration ---
        // 16000 Hz * 16-bit (2 bytes) = 32,000 bytes per second
        double durationMs = (payload.length / 32000.0) * 1000;

        // --- FILTER: Anti-Hallucination Guard ---
        if (durationMs < MIN_PHRASE_DURATION_MS) {
            log.debug("Dropped short noise ({}ms) - Ignored.", (int) durationMs);
            return;
        }

        log.info("Sentence captured ({}ms). Sending to AI...", (int) durationMs);

        // Proceed to Step 3
        dispatchToAi(payload, onResult);
    }

    private void dispatchToAi(byte[] payload, Consumer<String> onResult) {
        // Fire and Forget (Non-blocking)
        CompletableFuture.runAsync(() -> {
            try {
                // This blocks the WORKER thread, not the AUDIO thread
                String text = transcribe(payload);

                if (text != null && !text.isBlank()) {
                    log.info(">> AI Recognized: [{}]", text);
                    onResult.accept(text);
                }
            } catch (Exception e) {
                log.error("Async transcription error", e);
            }
        });
    }

    private String transcribe(byte[] audio) {
        try {
            byte[] wavData = addWavHeader(audio);
            // CRITICAL: Ensure no spaces in boundary
            String boundary = "JavaBoundary" + System.currentTimeMillis();

            // This builds the standard OpenAI-compatible multipart format
            byte[] multipartBody = buildMultipartBody(wavData, boundary);

            HttpRequest request = HttpRequest.newBuilder().uri(URI.create("http://localhost:8000/v1/audio/transcriptions")).header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody)).build();

            // Log exactly what we are about to send
            log.info("Sending request to Whisper (Payload size: {} bytes)...", multipartBody.length);

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            // ALWAYS log the response, even if it's empty
            log.info("Whisper HTTP {} | Raw Response: [{}]", response.statusCode(), response.body());

            if (response.statusCode() != 200) {
                log.error("Whisper API error: {} - {}", response.statusCode(), response.body());
                return null;
            }

            // LOG THE RAW JSON RESULT
            log.info("Whisper Result: {}", response.body());
            JsonNode node = objectMapper.readTree(response.body());
            String text = node.path("text").asText("");
            return text.trim(); // Returns JSON with "text" field
        } catch (Exception e) {
            log.error("Transcription failed: {}", e.getMessage());
            return null;
        }
    }

    private byte[] buildMultipartBody(byte[] wavData, String boundary) throws Exception {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        String lineBreak = "\r\n";

        // 1. FILE PART
        os.write(("--" + boundary + lineBreak).getBytes());
        os.write(("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"" + lineBreak).getBytes());
        os.write(("Content-Type: audio/wav" + lineBreak + lineBreak).getBytes());
        os.write(wavData);
        os.write(lineBreak.getBytes());

        // 2. MODEL PART
        os.write(("--" + boundary + lineBreak).getBytes());
        os.write(("Content-Disposition: form-data; name=\"model\"" + lineBreak + lineBreak).getBytes());
        os.write((MODEL_NAME + lineBreak).getBytes());  // Use large for Ukrainian + English mix

        // 3. LANGUAGE PART (force Ukrainian)
        os.write(("--" + boundary + lineBreak).getBytes());
        os.write(("Content-Disposition: form-data; name=\"language\"" + lineBreak + lineBreak).getBytes());
        os.write(("uk" + lineBreak).getBytes());

        // 4. PROMPT PART (prime for English tech terms in Ukrainian)
        os.write(("--" + boundary + lineBreak).getBytes());
        os.write(("Content-Disposition: form-data; name=\"prompt\"" + lineBreak + lineBreak).getBytes());
        os.write(("Обговорення програмування: Java, Angular, class, SQL, database та інше." + lineBreak).getBytes());  // Example prompt in Ukrainian with English terms

        // 5. END BOUNDARY
        os.write(("--" + boundary + "--" + lineBreak).getBytes());

        return os.toByteArray();
    }

    private void warmUpModel() {
        log.info("Warming up Whisper model (forcing RAM allocation)...");

        byte[] warmupPayload = new byte[SAMPLE_RATE * 2];

        // We leave the array as all zeros (Silence).
        // Whisper handles pure silence fine for a warmup; it just returns empty text.

        try {
            // We call transcribe() directly (blocking), not dispatchToAi()
            // We want the app to WAIT here until the model is ready.
            String result = transcribe(warmupPayload);
            log.info("Whisper model is WARM. Init result: [{}]", result);
        } catch (Exception e) {
            log.warn("Warmup warning: Model might still be loading. {}", e.getMessage());
        }
    }

    private String startContainer() {
        log.info("Cleaning up existing Whisper containers on port 8000...");
        try {
            // 1. Find and remove any container already using port 8000
            dockerClient.listContainersCmd()
                    .withShowAll(true)
                    .withLabelFilter(java.util.Map.of(ENGINE_LABEL_KEY, ENGINE_LABEL_VALUE))
                    .exec().forEach(c -> {
                        log.info("Removing managed container: {}", c.getId());
                        try {
                            dockerClient.removeContainerCmd(c.getId()).withForce(true).exec();
                        } catch (Exception ignored) {
                            log.info("Failed to remove container: {}", c.getId());
                        }
                    });
            // 2. Safety Fallback: Still check port 8000 specifically
            // (Handles containers not created by this app but blocking the port)
            dockerClient.listContainersCmd().withShowAll(true).exec().stream()
                    .filter(c -> java.util.Arrays.stream(c.getPorts())
                            .anyMatch(p -> p.getPublicPort() != null && p.getPublicPort() == 8000))
                    .forEach(c -> {
                        log.info("Removing orphan container on port 8000: {}", c.getId());
                        try {
                            dockerClient.removeContainerCmd(c.getId()).withForce(true).exec();
                        } catch (Exception ignored) {
                            log.info("Failed to remove orphan container on port 8000: {}", c.getId());
                        }
                    });
        } catch (Exception e) {
            log.warn("Cleanup failed, attempting to proceed anyway: {}", e.getMessage());
        }

        var portBindings = PortBinding.parse("8000:8000");

        // Named volume for persistence (managed by Docker; create if not exists)
        String volumeName = "speaches-hf-cache";

        // Create the named volume if it doesn't exist (idempotent)
        try {
            dockerClient.inspectVolumeCmd(volumeName).exec();
            log.info("Named volume '{}' already exists.", volumeName);
        } catch (com.github.dockerjava.api.exception.NotFoundException e) {
            log.info("Creating named volume '{}'.", volumeName);
            dockerClient.createVolumeCmd().withName(volumeName).exec();
        }

        var containerResponse = dockerClient.createContainerCmd("ghcr.io/speaches-ai/speaches:latest-cpu")
                .withLabels(java.util.Map.of(ENGINE_LABEL_KEY, ENGINE_LABEL_VALUE)) // <--- ADD LABEL
                .withEnv(
                        "PRELOAD_MODELS=[\"" + MODEL_NAME + "\"]",
                        "COMPUTE_TYPE=int8",
                        "INFERENCE_DEVICE=cpu",
                        "THREADS=8",
                        "OMP_NUM_THREADS=4",
                        "VAD_FILTER=true",
                        "BEAM_SIZE=1",
                        "TEMPERATURE=0",
                        // ANTI-HALLUCINATION SETTINGS (Supported by Speaches/Faster-Whisper)
                        // Prevents the model from getting stuck in a loop
                        "REPETITION_PENALTY=1.1",
                        "CONDITION_ON_PREVIOUS_TEXT=false" // Critical for voice commands to prevent context bleeding
                )
                .withHostConfig(HostConfig.newHostConfig()
                        .withPortBindings(portBindings)
                        .withBinds(new Bind(volumeName, new Volume("/home/ubuntu/.cache/huggingface/hub")))  // Mount named volume
                        .withAutoRemove(true))
                .exec();

        dockerClient.startContainerCmd(containerResponse.getId()).exec();
        return containerResponse.getId();
    }


    private byte[] addWavHeader(byte[] pcmAudio) {
        int totalDataLen = pcmAudio.length + 36;
        byte[] header = new byte[44];

        header[0] = 'R';
        header[1] = 'I';
        header[2] = 'F';
        header[3] = 'F';
        header[4] = (byte) (totalDataLen & 0xff);
        header[5] = (byte) ((totalDataLen >> 8) & 0xff);
        header[6] = (byte) ((totalDataLen >> 16) & 0xff);
        header[7] = (byte) ((totalDataLen >> 24) & 0xff);
        header[8] = 'W';
        header[9] = 'A';
        header[10] = 'V';
        header[11] = 'E';
        header[12] = 'f';
        header[13] = 'm';
        header[14] = 't';
        header[15] = ' ';
        header[16] = 16;
        header[17] = 0;
        header[18] = 0;
        header[19] = 0;                                             // Subchunk1Size
        header[20] = 1;
        header[21] = 0;                                             // AudioFormat (PCM = 1)
        header[22] = 1;
        header[23] = 0;                                             // NumChannels (Mono = 1)
        header[24] = (byte) (16000 & 0xff);                         // SampleRate (16000)
        header[25] = (byte) ((16000 >> 8) & 0xff);
        header[26] = (byte) ((16000 >> 16) & 0xff);
        header[27] = (byte) ((16000 >> 24) & 0xff);
        header[28] = (byte) (32000 & 0xff);                         // ByteRate (SampleRate * NumChannels * BitsPerSample/8)
        header[29] = (byte) ((32000 >> 8) & 0xff);
        header[30] = (byte) ((32000 >> 16) & 0xff);
        header[31] = (byte) ((32000 >> 24) & 0xff);
        header[32] = 2;
        header[33] = 0;                                             // BlockAlign
        header[34] = 16;
        header[35] = 0;                                             // BitsPerSample
        header[36] = 'd';
        header[37] = 'a';
        header[38] = 't';
        header[39] = 'a';
        header[40] = (byte) (pcmAudio.length & 0xff);
        header[41] = (byte) ((pcmAudio.length >> 8) & 0xff);
        header[42] = (byte) ((pcmAudio.length >> 16) & 0xff);
        header[43] = (byte) ((pcmAudio.length >> 24) & 0xff);

        byte[] wavFile = new byte[header.length + pcmAudio.length];
        System.arraycopy(header, 0, wavFile, 0, header.length);
        System.arraycopy(pcmAudio, 0, wavFile, header.length, pcmAudio.length);
        return wavFile;
    }

    /**
     * Calculates the "Loudness" (Root Mean Square) of a PCM audio chunk.
     * 16-bit audio is stored as 2 bytes per sample.
     */
    private double calculateRMS(byte[] rawData, int read) {
        long sum = 0;

        // Iterate 2 bytes at a time (16-bit samples)
        for (int i = 0; i < read; i += 2) {
            if (i + 1 >= read) break;

            // Convert low-byte and high-byte to a number
            // Little Endian: Low byte first, then High byte shifted 8 bits
            short sample = (short) ((rawData[i] & 0xFF) | (rawData[i + 1] << 8));

            // Add square of the sample to sum
            sum += sample * sample;
        }

        // Calculate average energy
        int sampleCount = read / 2;
        if (sampleCount == 0) return 0;

        return Math.sqrt(sum / (double) sampleCount);
    }

    @PreDestroy
    @Override
    public synchronized void close() {
        if (!isClosed.get()) {
            log.info("Closing WhisperEngine and releasing CPU resources...");

            // 1. Close local resources
            try {
                buffer.close();
            } catch (Exception e) {
                log.warn("Failed to close audio buffer", e);
            }

            // 2. Stop Docker container (auto-remove will handle deletion)
            if (containerId != null) {
                try {
                    log.info("Stopping Whisper container: {}", containerId);
                    dockerClient.stopContainerCmd(containerId).withTimeout(5).exec();

                    log.info("Whisper container stopped (auto-removed by Docker)");
                } catch (NotFoundException ignored) {
                    log.debug("Whisper container already removed");
                } catch (Exception e) {
                    log.warn("Failed to stop Whisper container!");
                }
            }
            try {
                dockerClient.close();
            } catch (Exception e) {
                log.debug("Docker client already closed");
            }
            this.isClosed.set(true);
        }
    }
}