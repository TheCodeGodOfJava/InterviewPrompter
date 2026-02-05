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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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

    // How long (in ms) we must hear silence before we assume the user stopped speaking.
    // 600ms is a natural conversational pause.
    private static final int PAUSE_BEFORE_SEND_MS = 600;

    // Minimum audio length. If audio is shorter than this (e.g., a keyboard click), ignore it.
    // This is the #1 defense against "Hallucinations".
    private static final int MIN_PHRASE_DURATION_MS = 800;
    private static final String MODEL_NAME = "Systran/faster-whisper-small";

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

    private long lastRmsLogTime = 0;

    // Default to a safe middle ground
    private volatile int silenceThreshold = 250;


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

    @Override
    public void setSilenceThreshold(int threshold) {
        this.silenceThreshold = threshold;
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

        // --- NEW: CALIBRATION LOGGING (Throttled) ---
        // Prints current volume vs threshold every 500ms
        if (now - lastRmsLogTime > 1000) {
            String status = currentRms > silenceThreshold ? "SPEAKING" : "SILENCE";
            log.info("[Audio Calibration] RMS: {} | Threshold: {} | Status: {}",
                    (int) currentRms, silenceThreshold, status);
            lastRmsLogTime = now;
        }
        // --------------------------------------------

        if (currentRms > silenceThreshold) {
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
        transcribe(payload).thenAccept(text -> {
            if (text != null && !text.isBlank()) {
                onResult.accept(text);
            }
        });
    }

    private CompletableFuture<String> transcribe(byte[] audio) {
        try {
            // 1. Prepare Payload (Fast, zero-copy methods we created earlier)
            byte[] wavData = addWavHeader(audio);
            String boundary = "JavaBoundary" + System.currentTimeMillis();
            byte[] multipartBody = buildMultipartBody(wavData, boundary);

            // 2. Build Request
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:8000/v1/audio/transcriptions"))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody))
                    .build();

            // 3. LOGGING: Use DEBUG for heavy payloads. Keep INFO clean.
            log.debug("Dispatching {} bytes to Whisper...", multipartBody.length);

            // --- TIMER START ---
            final long startTime = System.currentTimeMillis();

            // 4. ASYNC I/O: This is the magic.
            // It returns immediately. The HTTP Client handles the socket in the background.
            return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        // This runs LATER, when the response arrives
                        if (response.statusCode() != 200) {
                            log.error("Whisper API Error {}: {}", response.statusCode(), response.body());
                            return null;
                        }
                        try {
                            // Fast parsing
                            JsonNode node = objectMapper.readTree(response.body());
                            String text = node.path("text").asText("").trim();
                            long duration = System.currentTimeMillis() - startTime;
                            log.info(">> Speech recognized in {}ms: [{}]", duration, text);
                            return text;
                        } catch (Exception e) {
                            log.error("JSON Parsing Failed", e);
                            return null;
                        }
                    })
                    .exceptionally(ex -> {
                        log.error("Transcription Network Error", ex);
                        return null;
                    });

        } catch (Exception e) {
            log.error("Transcription Setup Failed", e);
            return CompletableFuture.completedFuture(null);
        }
    }

    private byte[] buildMultipartBody(byte[] wavData, String boundary) {
        // 1. Prepare Static Constants (US_ASCII is faster for headers)
        byte[] CRLF = "\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] BOUNDARY_LINE = ("--" + boundary + "\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] END_BOUNDARY = ("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII);

        // 2. Prepare Part Headers & Values
        byte[] fileHeader = ("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n" +
                "Content-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.US_ASCII);

        byte[] modelHeader = "Content-Disposition: form-data; name=\"model\"\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] modelValue = MODEL_NAME.getBytes(StandardCharsets.UTF_8);

        byte[] langHeader = "Content-Disposition: form-data; name=\"language\"\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] langValue = "uk".getBytes(StandardCharsets.UTF_8);

        byte[] tempHeader = "Content-Disposition: form-data; name=\"temperature\"\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] tempValue = "0".getBytes(StandardCharsets.US_ASCII);

        // Prompt needs UTF-8 for Ukrainian characters
        byte[] promptHeader = "Content-Disposition: form-data; name=\"prompt\"\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] promptValue = "Обговорення програмування: Java, Angular, class, SQL, database та інше.".getBytes(StandardCharsets.UTF_8);

        // 3. CALCULATE EXACT SIZE (To avoid array resizing)
        int totalSize = 0;

        // File Part
        totalSize += BOUNDARY_LINE.length + fileHeader.length + wavData.length + CRLF.length;
        // Model Part
        totalSize += BOUNDARY_LINE.length + modelHeader.length + modelValue.length + CRLF.length;
        // Language Part
        totalSize += BOUNDARY_LINE.length + langHeader.length + langValue.length + CRLF.length;
        // Temperature Part (Critical for Anti-Hallucination)
        totalSize += BOUNDARY_LINE.length + tempHeader.length + tempValue.length + CRLF.length;
        // Prompt Part
        totalSize += BOUNDARY_LINE.length + promptHeader.length + promptValue.length + CRLF.length;
        // Footer
        totalSize += END_BOUNDARY.length;

        // 4. ALLOCATE ONCE & FILL
        ByteBuffer buf = ByteBuffer.allocate(totalSize);

        buf.put(BOUNDARY_LINE).put(fileHeader).put(wavData).put(CRLF);
        buf.put(BOUNDARY_LINE).put(modelHeader).put(modelValue).put(CRLF);
        buf.put(BOUNDARY_LINE).put(langHeader).put(langValue).put(CRLF);
        buf.put(BOUNDARY_LINE).put(tempHeader).put(tempValue).put(CRLF); // Added Temperature=0
        buf.put(BOUNDARY_LINE).put(promptHeader).put(promptValue).put(CRLF);
        buf.put(END_BOUNDARY);

        return buf.array(); // Returns the backing array directly (No copy)
    }

    private void warmUpModel() {
        log.info("Warming up Whisper model (forcing RAM allocation)...");

        // 1 second of silence
        byte[] warmupPayload = new byte[SAMPLE_RATE * 2];

        try {
            // FIX: Use .join() to turn the Async Future back into a Synchronous wait.
            // This forces the app to pause here until Whisper replies.
            String result = transcribe(warmupPayload).join();

            log.info("Whisper model is WARM. Init result: [{}]", result);
        } catch (Exception e) {
            log.warn("Warmup warning: Model might still be loading or container not ready. {}", e.getMessage());
        }
    }

    private String startContainer() {
        log.info("Cleaning up existing Whisper containers on port 8000...");
        cleanupContainer();

        var portBindings = PortBinding.parse("8000:8000");

        // Named volume for persistence (managed by Docker; create if not exists)
        String volumeName = "speaches-hf-cache";
        createVolumeIfNotExists(volumeName);

        var containerResponse = dockerClient.createContainerCmd("ghcr.io/speaches-ai/speaches:latest-cpu")
                .withLabels(java.util.Map.of(ENGINE_LABEL_KEY, ENGINE_LABEL_VALUE)) // <--- ADD LABEL
                .withEnv(
                        "PRELOAD_MODELS=[\"" + MODEL_NAME + "\"]",
                        "COMPUTE_TYPE=int8",
                        "INFERENCE_DEVICE=cpu",
//                        "THREADS=8",
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

    private void createVolumeIfNotExists(String volumeName) {
        // Create the named volume if it doesn't exist (idempotent)
        try {
            dockerClient.inspectVolumeCmd(volumeName).exec();
            log.info("Named volume '{}' already exists.", volumeName);
        } catch (com.github.dockerjava.api.exception.NotFoundException e) {
            log.info("Creating named volume '{}'.", volumeName);
            dockerClient.createVolumeCmd().withName(volumeName).exec();
        }
    }

    private void cleanupContainer() {
        try {
            dockerClient.listContainersCmd().withShowAll(true).exec().stream()
                    .filter(c -> {
                        // Match by our Label OR by Port 8000
                        boolean isOurs = c.getLabels() != null && ENGINE_LABEL_VALUE.equals(c.getLabels().get(ENGINE_LABEL_KEY));
                        boolean isBlocking = java.util.Arrays.stream(c.getPorts())
                                .anyMatch(p -> p.getPublicPort() != null && p.getPublicPort() == 8000);
                        return isOurs || isBlocking;
                    })
                    .forEach(c -> {
                        log.info("Killing stale container blocking start: {}", c.getId());
                        try {
                            // KILL is faster than STOP. We don't care about graceful exit for stale containers.
                            dockerClient.killContainerCmd(c.getId()).exec();
                            // We don't need removeContainerCmd because AutoRemove=true handles it.
                        } catch (Exception ignored) {
                        }
                    });
        } catch (Exception e) {
            log.warn("Cleanup warning: {}", e.getMessage());
        }
    }

    private byte[] addWavHeader(byte[] pcmAudio) {
        // 44 bytes for header + raw audio length
        byte[] wavFile = new byte[44 + pcmAudio.length];

        // Wrap the array in a ByteBuffer to handle the bits easily
        ByteBuffer out = ByteBuffer.wrap(wavFile);
        out.order(ByteOrder.LITTLE_ENDIAN); // WAV standard is Little Endian

        // --- RIFF CHUNK ---
        out.put(new byte[]{'R', 'I', 'F', 'F'});
        out.putInt(36 + pcmAudio.length);         // Total file size - 8
        out.put(new byte[]{'W', 'A', 'V', 'E'});

        // --- FORMAT CHUNK ---
        out.put(new byte[]{'f', 'm', 't', ' '});
        out.putInt(16);                           // Size of format chunk (16 for PCM)
        out.putShort((short) 1);                        // Audio Format (1 = PCM)
        out.putShort((short) 1);                        // Channels (1 = Mono)
        out.putInt(SAMPLE_RATE);                        // Sample Rate (e.g., 16000)
        out.putInt(SAMPLE_RATE * 2);              // Byte Rate (Rate * Channels * BytesPerSample)
        out.putShort((short) 2);                        // Block Align (Channels * BytesPerSample)
        out.putShort((short) 16);                       // Bits Per Sample

        // --- DATA CHUNK ---
        out.put(new byte[]{'d', 'a', 't', 'a'});
        out.putInt(pcmAudio.length);                    // Actual size of audio data

        // Copy the raw audio into the rest of the array
        System.arraycopy(pcmAudio, 0, wavFile, 44, pcmAudio.length);

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