package com.threadconv.worker;

import com.threadconv.worker.converter.ConversionTask;
import com.threadconv.worker.model.WorkRequest;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns the worker's bounded ThreadPoolExecutor and idempotency guard.
 *
 * Concurrency design:
 *  - corePoolSize == maxPoolSize so threads are created eagerly (same fix
 *    as the API service — avoids the LinkedBlockingQueue starvation trap)
 *  - ConcurrentHashMap.putIfAbsent provides lock-free idempotency: a jobId
 *    that is already active is rejected with DUPLICATE (HTTP 409) so that
 *    API retries are safely absorbed
 *  - AbortPolicy + bounded queue: when all threads and queue slots are full
 *    the caller gets QUEUE_FULL (HTTP 503) and can back off
 */
@Service
public class WorkerService {

    public enum SubmitResult { ACCEPTED, DUPLICATE, QUEUE_FULL }

    private static final int MAX_QUEUE_CAPACITY = 200;

    @Value("${app.ffmpeg-path:ffmpeg}")
    private String ffmpegPath;

    private final ApiCallbackClient callback;
    private final ThreadPoolExecutor executor;

    private final ConcurrentHashMap<String, Boolean> activeJobIds = new ConcurrentHashMap<>();
    private final AtomicLong totalProcessed = new AtomicLong();
    private final AtomicLong totalFailed    = new AtomicLong();

    public WorkerService(ApiCallbackClient callback) {
        this.callback = callback;

        int cores   = Runtime.getRuntime().availableProcessors();
        int workers = Math.max(2, cores - 1);

        BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(MAX_QUEUE_CAPACITY);

        this.executor = new ThreadPoolExecutor(
                workers, workers, 0L, TimeUnit.MILLISECONDS, queue,
                r -> {
                    Thread t = new Thread(r, "worker-" + System.nanoTime());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );

        System.out.printf("[Worker] %d threads ready, queue cap %d, port 3003%n",
                workers, MAX_QUEUE_CAPACITY);
    }

    public SubmitResult submit(WorkRequest req) {
        // Idempotency: reject if same jobId is already being processed
        if (activeJobIds.putIfAbsent(req.jobId, Boolean.TRUE) != null) {
            System.out.printf("[Worker] DUPLICATE jobId=%s — already active, skipping%n", req.jobId);
            return SubmitResult.DUPLICATE;
        }

        try {
            executor.submit(new ConversionTask(
                    req,
                    ffmpegPath,
                    callback,
                    () -> { activeJobIds.remove(req.jobId); totalProcessed.incrementAndGet(); },
                    () -> { activeJobIds.remove(req.jobId); totalFailed.incrementAndGet(); }
            ));
            System.out.printf("[Worker] ACCEPTED jobId=%s format=%s%n", req.jobId, req.targetFormat);
            return SubmitResult.ACCEPTED;
        } catch (RejectedExecutionException e) {
            activeJobIds.remove(req.jobId);
            System.out.printf("[Worker] QUEUE_FULL jobId=%s%n", req.jobId);
            return SubmitResult.QUEUE_FULL;
        }
    }

    public Map<String, Object> getStats() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("activeWorkers",  executor.getActiveCount());
        s.put("maxWorkers",     executor.getMaximumPoolSize());
        s.put("queueSize",      executor.getQueue().size());
        s.put("activeJobCount", activeJobIds.size());
        s.put("totalProcessed", totalProcessed.get());
        s.put("totalFailed",    totalFailed.get());
        return s;
    }

    @PreDestroy
    public void shutdown() {
        System.out.println("[Worker] shutting down — draining queue...");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS))
                executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
