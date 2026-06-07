package com.threadconv.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.threadconv.model.Job;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * HTTP client used by the API service to communicate with the Worker service.
 *
 * dispatch() sends a job for processing (network boundary).
 * fetchStats() pulls live worker metrics to merge into /api/stats.
 *
 * Both calls are synchronous from the caller's perspective but the actual
 * conversion runs asynchronously inside the worker and reports back via
 * the /api/internal/* callback endpoints.
 */
@Component
public class WorkerClient {

    @Value("${app.worker-url:http://localhost:3003}")
    private String workerUrl;

    @Value("${app.api-callback-base-url:http://localhost:3001}")
    private String apiCallbackBaseUrl;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public enum DispatchResult { ACCEPTED, DUPLICATE, QUEUE_FULL, WORKER_UNREACHABLE }

    public DispatchResult dispatch(Job job) {
        // Absolute paths so the worker can locate files regardless of its cwd
        String inputPath  = Path.of(job.getInputPath()).toAbsolutePath().toString().replace("\\", "/");
        String outputPath = Path.of(job.getOutputPath()).toAbsolutePath().toString().replace("\\", "/");

        String body = String.format(
                "{\"jobId\":\"%s\",\"inputPath\":\"%s\",\"outputPath\":\"%s\"," +
                "\"originalName\":\"%s\",\"targetFormat\":\"%s\",\"callbackBaseUrl\":\"%s\"}",
                job.getJobId(), inputPath, outputPath,
                escape(job.getOriginalName()), job.getTargetFormat(),
                apiCallbackBaseUrl);

        System.out.printf("[API] Dispatching jobId=%s to %s%n", job.getJobId(), workerUrl);

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(workerUrl + "/worker/convert"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return switch (resp.statusCode()) {
                case 200 -> DispatchResult.ACCEPTED;
                case 409 -> DispatchResult.DUPLICATE;
                case 503 -> DispatchResult.QUEUE_FULL;
                default  -> DispatchResult.WORKER_UNREACHABLE;
            };
        } catch (Exception e) {
            System.err.printf("[API] Worker unreachable for jobId=%s: %s%n", job.getJobId(), e.getMessage());
            return DispatchResult.WORKER_UNREACHABLE;
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> fetchStats() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(workerUrl + "/worker/stats"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200)
                return objectMapper.readValue(resp.body(), Map.class);
        } catch (Exception e) {
            System.err.printf("[API] Could not fetch worker stats: %s%n", e.getMessage());
        }
        return Map.of("status", "worker_unreachable");
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
