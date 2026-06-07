package com.threadconv.controller;

import org.springframework.web.bind.annotation.*;

import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/**
 * Sequential vs parallel benchmark for one CPU-bound task.
 *
 * Task: compute SHA-256 over a 16 MB synthetic byte buffer, repeated N times.
 * Hashing is purely CPU-bound (no I/O, no network, no FFmpeg), so the measured
 * wall-clock difference is driven entirely by parallelism.
 *
 * GET /api/benchmark?count=8
 *
 * Example response:
 * {
 *   "task":          "SHA-256 hash of 16 MB buffer",
 *   "count":         8,
 *   "threads":       7,
 *   "sequentialMs":  1840,
 *   "parallelMs":    312,
 *   "speedupFactor": "5.9x",
 *   "runs": [ { "mode": "sequential", "run": 1, "ms": 230 }, ... ]
 * }
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class BenchmarkController {

    // 16 MB — enough to make each hash call take ~100-300 ms
    private static final int BUFFER_SIZE_MB = 16;
    private static final byte[] PAYLOAD = makePayload(BUFFER_SIZE_MB * 1024 * 1024);

    @GetMapping("/benchmark")
    public Map<String, Object> benchmark(
            @RequestParam(defaultValue = "8") int count) throws Exception {

        count = Math.max(1, Math.min(count, 32));

        int threads = Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
        List<Map<String, Object>> runs = new ArrayList<>();

        // ── Sequential ────────────────────────────────────────────────────────
        long seqStart = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            long t = System.currentTimeMillis();
            hashPayload();
            long ms = System.currentTimeMillis() - t;
            runs.add(Map.of("mode", "sequential", "run", i + 1, "ms", ms));
        }
        long seqMs = System.currentTimeMillis() - seqStart;

        // ── Parallel ──────────────────────────────────────────────────────────
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long parStart = System.currentTimeMillis();

        List<CompletableFuture<Long>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                long t = System.currentTimeMillis();
                hashPayload();
                return System.currentTimeMillis() - t;
            }, pool));
        }

        for (int i = 0; i < futures.size(); i++) {
            long ms = futures.get(i).get();
            runs.add(Map.of("mode", "parallel", "run", i + 1, "ms", ms));
        }
        long parMs = System.currentTimeMillis() - parStart;
        pool.shutdown();

        double speedup = parMs > 0
                ? Math.round((seqMs / (double) parMs) * 10) / 10.0
                : 0;

        System.out.printf("[Benchmark] count=%d threads=%d seq=%dms par=%dms speedup=%.1fx%n",
                count, threads, seqMs, parMs, speedup);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("task",          "SHA-256 hash of " + BUFFER_SIZE_MB + " MB buffer");
        result.put("count",         count);
        result.put("threads",       threads);
        result.put("sequentialMs",  seqMs);
        result.put("parallelMs",    parMs);
        result.put("speedupFactor", speedup + "x");
        result.put("runs",          runs);
        return result;
    }

    private static void hashPayload() {
        try {
            MessageDigest.getInstance("SHA-256").digest(PAYLOAD);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] makePayload(int size) {
        byte[] buf = new byte[size];
        for (int i = 0; i < size; i++) buf[i] = (byte) (i ^ (i >> 8));
        return buf;
    }
}
