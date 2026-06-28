# THREADCONV — Load Test Results

Machine: 8-core dev machine · Worker pool: 7 threads + `LinkedBlockingQueue(200)`
· JDK 21 · captured via `node code/stress-advanced.js` + `GET /api/stats`.

## Throughput & latency (50 concurrent video conversions, isolated run)

| Metric | Value |
|---|---|
| Jobs submitted | 50 |
| Completed | 50 |
| Failed | 0 |
| Success rate | 100.00% |
| Wall-clock time | 5.14 s |
| Throughput (wall-clock) | ≈ 9.7 jobs/s |
| p50 latency | 2556 ms |
| p95 latency | 4664 ms |
| p99 latency | 4838 ms |

## Bounded-queue overload (240-request burst — Scenario B)

| Metric | Value |
|---|---|
| Burst submitted | 240 |
| Accepted (7 threads + queue 200) | 207 |
| Rejected with HTTP 503 | 33 |
| `totalRejected` delta | +33 |

Accepted count equals the configured capacity (7 + 200) exactly — backpressure is
deterministic. See [FAILURE-INJECTION.md](../FAILURE-INJECTION.md).

## Sequential vs parallel CPU benchmark (`GET /api/benchmark?count=8`)

| Mode | Time | Speedup |
|---|---|---|
| Sequential (1 thread) | 153 ms | 1.0× |
| Parallel (7 threads, CompletableFuture) | 39 ms | **3.9×** |

Task = SHA-256 of a 16 MB buffer, ×8. Near-linear speedup confirms the CPU-bound
stage scales with the pool size.
