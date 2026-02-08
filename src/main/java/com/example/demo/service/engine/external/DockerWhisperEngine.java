package com.example.demo.service.engine.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Runs a local Docker container with Faster-Whisper.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "speech.engine", havingValue = "whisper", matchIfMissing = true)
public class DockerWhisperEngine extends AbstractSpeechEngine {

    private final ObjectMapper objectMapper;
    private final HttpClient client;
    private final DockerClient dockerClient;
    private String containerId;

    // Constants
    private static final String MODEL_NAME = "Systran/faster-whisper-small";
    private static final String ENGINE_LABEL_KEY = "managed-by";
    private static final String ENGINE_LABEL_VALUE = "interview-prompter-whisper";

    public DockerWhisperEngine(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build();
        this.dockerClient = createDockerClient();
    }

    @PostConstruct
    public void initSync() {
        log.info("--- Initializing Local Docker Whisper Engine ---");
        ensureImageExists();
        this.containerId = startContainer();
        waitForHealth();
        ensureModelDownloaded();
        warmUpModel();
        log.info("--- Local Whisper Engine Ready ---");
    }

    protected CompletableFuture<String> transcribe(byte[] audio) {
        try {
            // 1. Prepare Payload (Fast, zero-copy methods we created earlier)
            byte[] wavData = addWavHeader(audio);
            String boundary = "JavaBoundary" + System.currentTimeMillis();

            Map<String, String> params = Map.of(
                    "model", "Systran/faster-whisper-small",
                    "language", "uk",
                    "temperature", "0",
                    "prompt", "Обговорення програмування: Java, Angular, class, SQL, database та інше."
            );
            byte[] multipartBody = buildMultipartBody(wavData, boundary, params);
            // 2. Build Request
            HttpRequest request = newRequest("http://localhost:8000/v1/audio/transcriptions")
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody))
                    .build();

            log.debug("Dispatching {} bytes to Whisper...", multipartBody.length);

            // --- TIMER START ---
            final long startTime = System.currentTimeMillis();

            // 3. ASYNC I/O
            // It returns immediately. The HTTP Client handles the socket in the background.
            return client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
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
                    log.info("Speech recognized in {}ms: [{}]", duration, text);
                    return text;
                } catch (Exception e) {
                    log.error("JSON Parsing Failed", e);
                    return null;
                }
            }).exceptionally(ex -> {
                log.error("Transcription Network Error", ex);
                return null;
            });

        } catch (Exception e) {
            log.error("Transcription Setup Failed", e);
            return CompletableFuture.completedFuture(null);
        }
    }

    private void warmUpModel() {
        log.info("Warming up Whisper model (forcing RAM allocation)...");
        // Create 1 second of silence using constant from parent class
        byte[] warmupPayload = new byte[SAMPLE_RATE * 2];
        try {
            // Join() forces synchronous wait
            String result = transcribe(warmupPayload).join();
            log.info("Whisper model is WARM. Init result: [{}]", result);
        } catch (Exception e) {
            log.warn("Warmup warning: {}", e.getMessage());
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
                .withLabels(java.util.Map.of(ENGINE_LABEL_KEY, ENGINE_LABEL_VALUE))
                .withEnv("PRELOAD_MODELS=[\"" + MODEL_NAME + "\"]", "COMPUTE_TYPE=int8", "INFERENCE_DEVICE=cpu", "OMP_NUM_THREADS=4", "VAD_FILTER=true", "BEAM_SIZE=1", "TEMPERATURE=0", "REPETITION_PENALTY=1.1", "CONDITION_ON_PREVIOUS_TEXT=false")
                .withHostConfig(HostConfig.newHostConfig()
                        .withPortBindings(portBindings).withBinds(new Bind(volumeName, new Volume("/home/ubuntu/.cache/huggingface/hub")))
                        .withAutoRemove(true))
                .exec();

        dockerClient.startContainerCmd(containerResponse.getId()).exec();
        return containerResponse.getId();
    }

    private void ensureModelDownloaded() {
        log.info("Waiting for model {} to be fully cached and loaded...", MODEL_NAME);
        triggerDownloadViaApi();
        int attempts = 0;
        while (attempts < 300) {
            try {
                HttpRequest request = newRequest("http://localhost:8000/v1/models").GET().build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                // We only proceed if our model is present in the list of models.
                if (response.statusCode() == 200 && response.body().contains(MODEL_NAME)) {
                    log.info("SUCCESS: Model {} is verified and ready!", MODEL_NAME);
                    return;
                }
                if (attempts % 6 == 0) printLatestDockerLogs();
                Thread.sleep(5000);
            } catch (Exception e) {
                log.info("Next attempt: {} ", attempts);
            } finally {
                attempts++;
            }
        }
        throw new RuntimeException("Model failed to load!");
    }

    private void triggerDownloadViaApi() {
        String encoded = URLEncoder.encode(MODEL_NAME, StandardCharsets.UTF_8);
        String url = "http://localhost:8000/v1/models/" + encoded;
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).POST(HttpRequest.BodyPublishers.noBody()).build();
            client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenAccept(res -> log.info("Model download signal sent. Status: {}", res.statusCode()));
        } catch (Exception e) {
            log.error("Failed to signal download: {}", e.getMessage());
        }
    }

    private void printLatestDockerLogs() {
        try {
            dockerClient.logContainerCmd(containerId).withStdOut(true).withStdErr(true).withTail(20).exec(new ResultCallback.Adapter<>() {
                @Override
                public void onNext(Frame item) {
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
        DefaultDockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().withDockerHost("npipe:////./pipe/docker_engine").build();
        DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder().dockerHost(config.getDockerHost()).sslConfig(config.getSSLConfig()).maxConnections(100).connectionTimeout(Duration.ofSeconds(5)).responseTimeout(Duration.ofSeconds(30)).build();
        return DockerClientImpl.getInstance(config, httpClient);
    }

    private void ensureImageExists() {
        String imageName = "ghcr.io/speaches-ai/speaches:latest-cpu";
        log.info("Checking for image: {}", imageName);
        try {
            dockerClient.inspectImageCmd(imageName).exec();
        } catch (NotFoundException e) {
            log.info("Image not found. Pulling {} (This will take a few minutes, it could be several GBs)...", imageName);
            log.info("Pulling image {}...", imageName);
            try {
                dockerClient.pullImageCmd(imageName).start().awaitCompletion();
                log.info("Pull complete!");
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Image pull interrupted", ie);
            }
        }
    }

    private void createVolumeIfNotExists(String volumeName) {
        try {
            dockerClient.inspectVolumeCmd(volumeName).exec();
        } catch (NotFoundException e) {
            log.info("Creating named volume '{}'.", volumeName);
            dockerClient.createVolumeCmd().withName(volumeName).exec();
        }
    }

    private void cleanupContainer() {
        try {
            dockerClient.listContainersCmd().withShowAll(true).exec().stream().filter(c -> {
                boolean isOurs = c.getLabels() != null && ENGINE_LABEL_VALUE.equals(c.getLabels().get(ENGINE_LABEL_KEY));
                boolean isBlocking = java.util.Arrays.stream(c.getPorts()).anyMatch(p -> p.getPublicPort() != null && p.getPublicPort() == 8000);
                return isOurs || isBlocking;
            }).forEach(c -> {
                try {
                    dockerClient.killContainerCmd(c.getId()).exec();
                } catch (Exception ignored) {
                    log.info("Failed to cleanup container!");
                }
            });
        } catch (Exception e) {
            log.warn("Cleanup warning: {}", e.getMessage());
        }
    }

    private void waitForHealth() {
        log.info("Waiting for Whisper to load...");
        boolean isReady = false;
        int attempts = 0;
        while (!isReady && attempts < 60) {
            try {
                URL url = URI.create("http://localhost:8000/health").toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                if (conn.getResponseCode() == 200) isReady = true;
            } catch (Exception e) {
                log.info("Failed to connect to Whisper to load!");
            }
            if (!isReady) try {
                Thread.sleep(300);
            } catch (Exception ignored) {
                log.info("Failed to sleep after loading whisper!");
            }
            attempts++;
        }
        if (!isReady) throw new RuntimeException("Whisper service failed to start!");
        log.info("Whisper is ready on your CPU!");
    }

    @PreDestroy
    @Override
    public synchronized void close() {
        if (this.isClosed.get()) return;
        // Stop Docker container
        if (containerId != null) {
            try {
                log.info("Stopping Whisper container...");
                dockerClient.stopContainerCmd(containerId).withTimeout(5).exec();
            } catch (Exception e) {
                log.warn("Failed to stop container");
            }
        }
        try {
            dockerClient.close();
        } catch (Exception e) {
            log.debug("Docker client closed");
        }
        super.closeAudioBuffer();
        this.isClosed.set(true);
    }
}