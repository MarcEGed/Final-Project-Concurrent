package com.threadconv.service;

import com.threadconv.converter.ConversionTask;
import com.threadconv.model.Job;
import com.threadconv.model.JobStatus;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages a bounded ThreadPoolExecutor (Week 5 — ExecutorService, bounded
 * queues, backpressure).
 *
 * Concurrency guarantees:
 *  - MAX_QUEUE_CAPACITY: hard limit; beyond this the API returns HTTP 503
 *    (intentional overload behaviour, not silent dropping)
 *  - Worker count: capped at (CPU cores - 1), minimum 2, to leave one core
 *    free for the HTTP-request threads
 *  - Retry: failed jobs are resubmitted up to MAX_ATTEMPTS before being
 *    marked FAILED permanently
 *  - Metrics: AtomicLong counters + a bounded deque of recent latency samples
 *    used to compute p50/p95/p99 on demand
 *  - Graceful shutdown: @PreDestroy drains the queue (up to 60 s) then
 *    force-terminates remaining workers
 */
@Service
public class WorkerPoolService {

    private static final int MAX_QUEUE_CAPACITY = 500;
    private static final int MAX_ATTEMPTS       = 3;
    private static final int LATENCY_WINDOW     = 1000; // keep last N samples

    @Value("${app.ffmpeg-path:ffmpeg}")
    private String ffmpegPath;

    @Value("${app.upload-dir:./uploads}")
    private String uploadDir;

    @Value("${app.output-dir:./outputs}")
    private String outputDir;

    private final JobStoreService jobStore;
    private final SocketService   sockets;
    private final ThreadPoolExecutor executor;

    // ── Metrics ───────────────────────────────────────────────────────────────
    private final AtomicLong totalCompleted = new AtomicLong();
    private final AtomicLong totalFailed    = new AtomicLong();
    private final AtomicLong totalRejected  = new AtomicLong();
    private final long        startTime     = System.currentTimeMillis();

    /** Rolling window of job latencies (createdAt → completedAt) in ms. */
    private final Deque<Long> recentLatencies = new ArrayDeque<>(LATENCY_WINDOW);

    public WorkerPoolService(JobStoreService jobStore, SocketService sockets) {
        this.jobStore = jobStore;
        this.sockets  = sockets;

        int cores      = Runtime.getRuntime().availableProcessors();
        int maxWorkers = Math.max(2, cores - 1);

        // corePoolSize == maxWorkers: threads are created eagerly as jobs arrive.
        // ThreadPoolExecutor only spawns beyond core when the queue is FULL, so
        // a core < max configuration with a large queue (500) means extra threads
        // would never be created in practice. Setting core == max avoids that trap.
        BlockingQueue<Runnable> workQueue = new LinkedBlockingQueue<>(MAX_QUEUE_CAPACITY);

        this.executor = new ThreadPoolExecutor(
            maxWorkers, // core == max → spin up threads immediately, never idle-kill them
            maxWorkers,
            0L, TimeUnit.MILLISECONDS,
            workQueue,
            r -> {
                Thread t = new Thread(r, "converter-worker-" + System.nanoTime());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy() // throws RejectedExecutionException when full
        );

        System.out.printf("[WorkerPool] %d workers, queue cap %d%n",
                maxWorkers, MAX_QUEUE_CAPACITY);
    }

    /**
     * Submits a job for conversion.
     * @return true if accepted, false if queue is full (caller should return HTTP 503)
     */
    public boolean submit(Job job) {
        try {
            executor.submit(new ConversionTask(
                job, jobStore, sockets, ffmpegPath,
                () -> onSuccess(job),
                () -> onFailure(job)
            ));
            return true;
        } catch (RejectedExecutionException e) {
            job.setStatus(JobStatus.FAILED);
            job.setError("Server queue is full. Please try again later.");
            sockets.emitFailed(job.getJobId(), job.getError());
            totalRejected.incrementAndGet();
            return false;
        }
    }

    private void onSuccess(Job job) {
        totalCompleted.incrementAndGet();
        recordLatency(job);
    }

    private void onFailure(Job job) {
        job.incrementAttempts();
        if (job.getAttempts() < MAX_ATTEMPTS) {
            // Retry: reset status and resubmit
            job.setStatus(JobStatus.QUEUED);
            job.setError(null);
            job.setProgress(0);
            sockets.emitRequeued(job.getJobId(), job.getAttempts());
            submit(job); // recursive — bounded by MAX_ATTEMPTS
        } else {
            totalFailed.incrementAndGet();
            recordLatency(job);
        }
    }

    private synchronized void recordLatency(Job job) {
        if (job.getCreatedAt() > 0 && job.getCompletedAt() > 0) {
            if (recentLatencies.size() >= LATENCY_WINDOW) recentLatencies.pollFirst();
            recentLatencies.addLast(job.getCompletedAt() - job.getCreatedAt());
        }
    }

    // ── Stats / metrics ───────────────────────────────────────────────────────

    public Map<String, Object> getStats() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("activeWorkers",  executor.getActiveCount());
        s.put("maxWorkers",     executor.getMaximumPoolSize());
        s.put("queueSize",      executor.getQueue().size());
        s.put("totalCompleted", totalCompleted.get());
        s.put("totalFailed",    totalFailed.get());
        s.put("totalRejected",  totalRejected.get());

        long uptimeMs = System.currentTimeMillis() - startTime;
        double uptimeMin = uptimeMs / 60_000.0;
        s.put("throughputPerMinute",
              uptimeMin > 0 ? Math.round((totalCompleted.get() / uptimeMin) * 10) / 10.0 : 0.0);

        // Latency percentiles from the rolling window
        long[] latencyMs = getLatencyPercentiles();
        s.put("p50LatencyMs", latencyMs[0]);
        s.put("p95LatencyMs", latencyMs[1]);
        s.put("p99LatencyMs", latencyMs[2]);

        return s;
    }

    private synchronized long[] getLatencyPercentiles() {
        if (recentLatencies.isEmpty()) return new long[]{0, 0, 0};
        long[] sorted = recentLatencies.stream().mapToLong(Long::longValue).sorted().toArray();
        return new long[]{
            percentile(sorted, 50),
            percentile(sorted, 95),
            percentile(sorted, 99)
        };
    }

    private static long percentile(long[] sorted, int pct) {
        int idx = (int) Math.ceil(pct / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))];
    }

    // ── Cleanup cron — every 15 minutes, delete files older than 1 hour ───────
    @Scheduled(fixedRate = 15 * 60 * 1000)
    public void cleanupOldFiles() {
        long cutoff = System.currentTimeMillis() - 60 * 60 * 1000L;
        for (String dir : List.of(uploadDir, outputDir)) {
            File folder = new File(dir);
            if (!folder.exists()) continue;
            File[] files = folder.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (f.lastModified() < cutoff) f.delete();
            }
        }
        jobStore.clearCompleted();
        System.out.println("[WorkerPool] cleanup complete");
    }

    // ── Graceful shutdown ─────────────────────────────────────────────────────
    @PreDestroy
    public void shutdown() {
        System.out.println("[WorkerPool] shutting down — draining queue...");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                System.out.println("[WorkerPool] forced shutdown after 60 s");
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
