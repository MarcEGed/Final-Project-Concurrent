package com.threadconv.worker;

import com.threadconv.worker.model.WorkRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * HTTP entry point for the Worker service.
 *
 *  POST /worker/convert  — dispatch a conversion job (called by the API service)
 *  GET  /worker/stats    — thread pool and queue metrics
 *  GET  /worker/health   — liveness probe
 *
 * All endpoints are internal (API-to-worker); they are not called by the browser.
 */
@RestController
@RequestMapping("/worker")
public class WorkerController {

    private final WorkerService workerService;

    public WorkerController(WorkerService workerService) {
        this.workerService = workerService;
    }

    @PostMapping("/convert")
    public ResponseEntity<Map<String, Object>> convert(@RequestBody WorkRequest req) {
        if (req.jobId == null || req.jobId.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "jobId required"));
        if (req.inputPath == null || req.outputPath == null)
            return ResponseEntity.badRequest().body(Map.of("error", "inputPath and outputPath required"));

        WorkerService.SubmitResult result = workerService.submit(req);

        return switch (result) {
            case ACCEPTED   -> ResponseEntity.ok(Map.of("status", "accepted", "jobId", req.jobId));
            case DUPLICATE  -> ResponseEntity.status(409).body(Map.of("error", "duplicate",  "jobId", req.jobId));
            case QUEUE_FULL -> ResponseEntity.status(503).body(Map.of("error", "queue_full", "jobId", req.jobId));
        };
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        return ResponseEntity.ok(workerService.getStats());
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "ok", "service", "worker"));
    }
}
