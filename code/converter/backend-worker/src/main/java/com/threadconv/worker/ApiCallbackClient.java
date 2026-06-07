package com.threadconv.worker;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * HTTP client that posts job lifecycle events back to the API service.
 *
 * Uses Java's built-in HttpClient (no extra deps). Failures are logged but
 * never rethrown — a callback miss is unfortunate but must not crash the
 * worker thread that is processing other jobs.
 */
@Component
public class ApiCallbackClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public void reportProgress(String callbackBaseUrl, String jobId, int progress, String message) {
        String body = String.format(
                "{\"jobId\":\"%s\",\"progress\":%d,\"message\":\"%s\"}",
                jobId, progress, escape(message));
        post(callbackBaseUrl + "/api/internal/progress", body, jobId);
    }

    public void reportDone(String callbackBaseUrl, String jobId) {
        String body = String.format("{\"jobId\":\"%s\"}", jobId);
        post(callbackBaseUrl + "/api/internal/done", body, jobId);
    }

    public void reportFailed(String callbackBaseUrl, String jobId, String error) {
        String body = String.format(
                "{\"jobId\":\"%s\",\"error\":\"%s\"}",
                jobId, escape(error));
        post(callbackBaseUrl + "/api/internal/failed", body, jobId);
    }

    private void post(String url, String json, String jobId) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            http.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            System.err.printf("[Worker] Callback to %s failed (jobId=%s): %s%n", url, jobId, e.getMessage());
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }
}
