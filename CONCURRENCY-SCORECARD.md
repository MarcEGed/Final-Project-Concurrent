# THREADCONV — Concurrency Scorecard

The scorecard required by the project, plus the inventory of shared mutable state
and the mechanism that protects each piece. Code references are to the two Spring
Boot services under `code/converter/`.

## Scorecard

| Question | Mechanism used | Evidence |
|---|---|---|
| **Thread-safe?** | `ConcurrentHashMap` job store + content index; `AtomicLong` counters; `volatile` job fields; `ThreadPoolExecutor` with a thread-safe `LinkedBlockingQueue`; idempotency via `putIfAbsent`. No shared mutable state is touched without one of these. | 50/50 jobs correct under concurrent load, 0 corruption ([evidence/load-test.md](evidence/load-test.md)); 240-burst stays correct. |
| **Visibility guaranteed?** | Mutable `Job` fields are `volatile` (worker/callback thread writes → HTTP-thread reads, happens-before). `ConcurrentHashMap.put`→`get` also establishes happens-before. | Progress written by callback threads is read correctly by `/api/jobs` HTTP threads; `Job.java:22-27`. |
| **Deadlock-free?** | No nested locks. Lock-free structures (`ConcurrentHashMap`, atomics) dominate; the only `synchronized` is a single short critical section around `recentLatencies` that acquires no other lock. No lock ordering to violate. | `WorkerPoolService.recordLatency/getLatencyPercentiles` are the sole monitors; never call out while held. |
| **Liveness guaranteed?** | Bounded pool + bounded queue + `AbortPolicy` means submitters never block forever — work is either queued, run, or rejected fast. Network calls have connect/request timeouts (5s/10s/3s) so no unbounded wait on the boundary. | Scenario A returns a fast `FAILED` when the worker is dead instead of hanging ([FAILURE-INJECTION.md](FAILURE-INJECTION.md)). |
| **Bounded resources?** | `ThreadPoolExecutor(7, 7, …, LinkedBlockingQueue(200), AbortPolicy)`. No unbounded thread creation, no unbounded queue. | Scenario B: 240 submitted → exactly 207 accepted, 33 rejected with 503. |
| **Failure recovery path?** | Dispatch-time fallback (`WORKER_UNREACHABLE` → `FAILED`); conversion-failure retry up to 3× then permanent `FAILED`; idempotency guard makes retries safe; FFmpeg timeouts; graceful shutdown drains in-flight work. | Scenario A recovers after worker restart; `InternalCallbackController.failed()` retry loop. |

## Shared mutable state → protection

| Shared state | Where | Writers → readers | Protection |
|---|---|---|---|
| Job registry `jobs` | `JobStoreService` (API) | many HTTP threads + callback threads | `ConcurrentHashMap` (lock-striped) |
| Dedup index `contentIndex` | `JobStoreService` (API) | upload threads | `ConcurrentHashMap.putIfAbsent` (first-wins) |
| `Job.status/progress/message/error/completedAt` | `Job` (API) | worker-callback thread → HTTP threads | `volatile` (safe publication + visibility) |
| `Job.attempts` | `Job` (API) | failure-callback path only | `volatile`; mutated serially per jobId (idempotency guard ⇒ one active execution per job, so no concurrent `++`) |
| `totalCompleted/Failed/Rejected` | `WorkerPoolService` (API) | many callback/HTTP threads | `AtomicLong` (lock-free) |
| `recentLatencies` deque | `WorkerPoolService` (API) | callback threads → stats reader | `synchronized` (small critical section) |
| Idempotency set `activeJobIds` | `WorkerService` (Worker) | dispatch threads | `ConcurrentHashMap.putIfAbsent` / `remove` |
| Executor work queue | `WorkerService` (Worker) | dispatch threads → worker threads | `LinkedBlockingQueue(200)` (thread-safe, bounded) |
| `totalProcessed/totalFailed` | `WorkerService` (Worker) | worker threads | `AtomicLong` |

## Thread / executor sizing rationale

- **Worker pool = `max(2, cores − 1)` (7 on an 8-core box).** Conversions are
  CPU-bound (FFmpeg / hashing), so the optimum is ≈ number of cores; we reserve
  one core for the JVM, Tomcat I/O threads, and the OS.
- **`corePoolSize == maxPoolSize`.** `ThreadPoolExecutor` only grows past the core
  size once the queue is *full*; with a 200-slot queue it would otherwise stay at
  the core size and never parallelise. Setting core = max forces eager thread
  creation. (Documented in [ARCHITECTURE.md](ARCHITECTURE.md).)
- **Queue = 200.** Big enough to absorb normal bursts, small enough to fail fast
  and surface backpressure (503) instead of growing memory without bound.
