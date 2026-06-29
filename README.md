# ⚙️ THREADCONV — Multithreaded Web File Converter

A scalable, **distributed** web-based file-conversion system using multithreading,
a bounded job queue, an API↔Worker network boundary, and real-time progress.

> **Course project — Concurrency, Parallelism & Distributed Systems (Dr. M. Aoude).**
> **Selected topic: B — Machine-Learning / Data-Analysis Pipeline.** The app is a
> three-stage pipeline — **validate/pre-process** (upload validation + content
> hashing) → **convert** (the CPU-bound stage, parallelised across a bounded
> worker pool) → **post-process/store** (write output, emit completion). It
> includes the required sequential-vs-parallel benchmark for the CPU-bound step.

This project demonstrates concurrency, multithreading, a producer–consumer
architecture, bounded resources/backpressure, a real distributed network
boundary, idempotency, and failure recovery.

---

## 🏗️ Architecture (two processes + UI)

```
Browser (3000) ──POST /api/upload──► API Service (REST 3001 / Socket.IO 3002)
                                         │  POST /worker/convert (HTTP)
                                         ▼
                                     Worker Service (3003)
                                     ThreadPoolExecutor + bounded queue(200)
                                         │  POST /api/internal/{progress,done,failed}
                                         ▼
                                     API ─► Socket.IO ─► Browser (live progress)
```

- **API Service** (`code/converter/backend-java/`) — Spring Boot REST + Socket.IO,
  job store (`ConcurrentHashMap`), HTTP dispatcher, retry + metrics.
- **Worker Service** (`code/converter/backend-worker/`) — Spring Boot conversion
  engine: `ThreadPoolExecutor` (cores−1 threads) + `LinkedBlockingQueue(200)` with
  `AbortPolicy` for backpressure, idempotency guard, FFmpeg-based converters.
- **Frontend** (`code/converter/frontend/`) — React UI with drag-and-drop upload
  and a live progress dashboard.

Full design in [ARCHITECTURE.md](ARCHITECTURE.md) and [DIAGRAMS.md](DIAGRAMS.md).

---

## 🚀 Run it (local, no cloud)

**Prerequisites:** JDK 21 (`JAVA_HOME` must point to it), Node.js, FFmpeg on `PATH`.

```powershell
# set the JDK for every terminal (or set it permanently in System env vars)
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
```

| Terminal | Command (repo root) | Serves |
|---|---|---|
| 1 — Worker  | `.\run-worker.bat`   | conversion engine, :3003 |
| 2 — API     | `.\run-backend.bat`  | REST :3001 + Socket.IO :3002 |
| 3 — Frontend| `.\run-frontend.bat` | React UI, **http://localhost:3000** |

Start in order: **Worker → API → Frontend.** The `.bat` scripts build the Gradle
JARs on first run (no global Gradle install needed). Full detail in
[ARCHITECTURE.md](ARCHITECTURE.md).

---

## 🧪 Test it

```powershell
cd code
node stress-advanced.js        # concurrency / load test (50 unique videos)
node failure-injection.js      # two injected failure scenarios (A + B)
```

- **Load test & metrics:** [STRESS-TESTING.md](STRESS-TESTING.md) ·
  results table in [evidence/load-test.md](evidence/load-test.md)
  (50 jobs, 100% success, p50 2556 / p95 4664 / p99 4838 ms).
- **Failure injection:** [FAILURE-INJECTION.md](FAILURE-INJECTION.md) — (A) worker
  kill → fallback → recovery; (B) bounded-queue overload → 503 backpressure.
  Captured output in [evidence/](evidence/).
- **Live metrics** (throughput, p50/p95/p99, queue depth, completed/failed/rejected):
  `GET http://localhost:3001/api/stats`
- **Sequential vs parallel benchmark:** `GET http://localhost:3001/api/benchmark?count=8`

---

## 🧵 What it demonstrates

| Concept | Where |
|---|---|
| Producer–consumer + bounded queue | Worker `ThreadPoolExecutor` + `LinkedBlockingQueue(200)` |
| Backpressure / bounded resources | `AbortPolicy` → HTTP 503 → job FAILED + `totalRejected` |
| Parallel speedup (CPU-bound) | `/api/benchmark` — sequential vs parallel SHA-256 |
| Distributed network boundary | API ↔ Worker over HTTP, `jobId` as correlation ID |
| Idempotency | `ConcurrentHashMap.putIfAbsent(jobId)` guard in Worker |
| Failure recovery | retry (≤3) + worker-down fallback + restart recovery |
| Safe shared state | `ConcurrentHashMap` job store, `volatile` job fields, `AtomicLong` counters |
| Real-time updates | Socket.IO `job:progress` / `job:done` / `job:failed` |

---

## 📚 Submission artifacts

| Document | What |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | System design, components, ports, change log |
| [DIAGRAMS.md](DIAGRAMS.md) | Architecture, async sequence, failure-propagation diagrams |
| [CONCURRENCY-SCORECARD.md](CONCURRENCY-SCORECARD.md) | Scorecard + shared-state inventory + sizing rationale |
| [DECISION-MEMO.md](DECISION-MEMO.md) | Architecture decision memo (Q1–Q5) |
| [FAILURE-INJECTION.md](FAILURE-INJECTION.md) | Two injected failure scenarios + how to reproduce |
| [STRESS-TESTING.md](STRESS-TESTING.md) | Load/stress test guide |
| [evidence/](evidence/) | Captured failure-injection output, API logs, load-test table |
