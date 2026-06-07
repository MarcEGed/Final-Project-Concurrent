package com.threadconv.controller;

import com.threadconv.model.Job;
import com.threadconv.model.JobStatus;
import com.threadconv.service.JobStoreService;
import com.threadconv.service.SocketService;
import com.threadconv.service.WorkerPoolService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Receives asynchronous progress/done/failed callbacks from the Worker service.
 *
 * These endpoints are part of the distributed protocol:
 *
 *   Worker → POST /api/internal/progress  — job is in flight, update progress bar
 *   Worker → POST /api/internal/done      — conversion succeeded
 *   Worker → POST /api/internal/failed    — conversion failed; may trigger retry
 *
 * On each callback the API updates the in-memory job store and emits the
 * corresponding Socket.IO event so the browser gets a live update.
 *
 * The jobId in every request body is the distributed correlation ID — it
 * appears in the worker log, in this log, and in every Socket.IO event, making
 * the full lifecycle of each job traceable across both processes.
 */
@RestController
@RequestMapping("/api/internal")
public class InternalCallbackController {

    private static final int MAX_ATTEMPTS = 3;

    private final JobStoreService   jobStore;
    private final SocketService     sockets;
    private final WorkerPoolService workerPool;

    public InternalCallbackController(JobStoreService jobStore,
                                      SocketService sockets,
                                      WorkerPoolService workerPool) {
        this.jobStore   = jobStore;
        this.sockets    = sockets;
        this.workerPool = workerPool;
    }

    @PostMapping("/progress")
    public ResponseEntity<Void> progress(@RequestBody Map<String, Object> body) {
        String jobId   = (String) body.get("jobId");
        int    pct     = ((Number) body.get("progress")).intValue();
        String message = (String) body.getOrDefault("message", "");

        Job job = jobStore.get(jobId);
        if (job != null) {
            job.setStatus(JobStatus.PROCESSING);
            job.setProgress(pct);
            job.setProgressMessage(message);
            sockets.emitProgress(jobId, pct, message);
            System.out.printf("[API] PROGRESS jobId=%s  %3d%%  %s%n", jobId, pct, message);
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping("/done")
    public ResponseEntity<Void> done(@RequestBody Map<String, Object> body) {
        String jobId = (String) body.get("jobId");

        Job job = jobStore.get(jobId);
        if (job != null) {
            job.setStatus(JobStatus.COMPLETED);
            job.setProgress(100);
            job.setCompletedAt(System.currentTimeMillis());
            sockets.emitDone(jobId);
            workerPool.recordCompletion(job);
            System.out.printf("[API] DONE     jobId=%s%n", jobId);
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping("/failed")
    public ResponseEntity<Void> failed(@RequestBody Map<String, Object> body) {
        String jobId = (String) body.get("jobId");
        String error = (String) body.getOrDefault("error", "Conversion failed");

        Job job = jobStore.get(jobId);
        if (job == null) return ResponseEntity.ok().build();

        job.incrementAttempts();
        System.out.printf("[API] FAILED   jobId=%s  attempt=%d  error=%s%n",
                jobId, job.getAttempts(), error);

        if (job.getAttempts() < MAX_ATTEMPTS) {
            // Retry: reset state and resubmit to the worker
            job.setStatus(JobStatus.QUEUED);
            job.setError(null);
            job.setProgress(0);
            sockets.emitRequeued(jobId, job.getAttempts());
            workerPool.resubmit(job);
        } else {
            job.setStatus(JobStatus.FAILED);
            job.setError(error);
            job.setCompletedAt(System.currentTimeMillis());
            sockets.emitFailed(jobId, error);
            workerPool.recordFailure(job);
        }
        return ResponseEntity.ok().build();
    }
}
