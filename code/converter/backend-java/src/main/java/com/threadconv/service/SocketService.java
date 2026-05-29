package com.threadconv.service;

import com.corundumstudio.socketio.Configuration;
import com.corundumstudio.socketio.SocketIOServer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Wraps a netty-socketio server so the existing React socket.io-client
 * connects without any protocol changes.
 *
 * Runs on a separate port (3002) from the Spring REST API (3001).
 * The frontend's socket.js only needs its port updated.
 *
 * Events emitted match the Node.js backend exactly:
 *   job:processing  { jobId }
 *   job:progress    { jobId, progress, message }
 *   job:done        { jobId }
 *   job:failed      { jobId, error }
 *   job:requeued    { jobId, attempt }
 */
@Service
public class SocketService {

    @Value("${app.socket-port:3002}")
    private int socketPort;

    private SocketIOServer server;

    @PostConstruct
    public void start() {
        Configuration config = new Configuration();
        config.setHostname("0.0.0.0");
        config.setPort(socketPort);
        config.setAllowCustomRequests(true);

        // Allow the React dev server origin
        config.setOrigin("*");

        server = new SocketIOServer(config);
        server.start();
        System.out.println("[SocketService] Socket.IO listening on port " + socketPort);
    }

    @PreDestroy
    public void stop() {
        if (server != null) server.stop();
    }

    public void emit(String event, Map<String, Object> data) {
        if (server != null) {
            server.getBroadcastOperations().sendEvent(event, data);
        }
    }

    // ── Typed helpers so call-sites are not littered with Map.of() ────────────

    public void emitProcessing(String jobId) {
        emit("job:processing", Map.of("jobId", jobId, "status", "processing"));
    }

    public void emitProgress(String jobId, int pct, String message) {
        emit("job:progress", Map.of("jobId", jobId, "progress", pct, "message", message));
    }

    public void emitDone(String jobId) {
        emit("job:done", Map.of("jobId", jobId, "status", "completed"));
    }

    public void emitFailed(String jobId, String error) {
        emit("job:failed", Map.of("jobId", jobId, "status", "failed", "error", error));
    }

    public void emitRequeued(String jobId, int attempt) {
        emit("job:requeued", Map.of("jobId", jobId, "attempt", attempt));
    }
}
