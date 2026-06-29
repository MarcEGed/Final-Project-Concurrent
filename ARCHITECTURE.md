# THREADCONV — Architecture & Change Log

What the project is, what changed from the original Node.js version, and a running log of every significant change made during development.

---

## What the Project Does

THREADCONV is a concurrent, distributed file-conversion web app. Users upload files in the browser, the backend distributes conversion work across two separate processes, and live progress is streamed back via Socket.IO.

| Input type | Supported formats |
|---|---|
| Images (jpg, png, webp, gif, tiff…) | jpg, png, webp, gif, tiff, avif |
| Video (mp4, mkv, avi, mov, webm…) | mp4, mkv, avi, mov, webm |
| Text (txt, md, json, html, csv, xml) | txt, md, json, html |

---

## Current Architecture (Distributed — Two Processes)

```
Browser (port 3000)
  │
  │  POST /api/upload  (multipart)
  ▼
┌─────────────────────────────────────────────┐
│  API Service  —  ports 3001 (REST) / 3002   │
│                  (Socket.IO)                │
│                                             │
│  ApiController        — handles uploads     │
│  JobStoreService      — ConcurrentHashMap   │
│  WorkerPoolService    — HTTP dispatcher     │
│  WorkerClient         — calls Worker HTTP   │
│  InternalCallback…    — receives callbacks  │
│  SocketService        — Socket.IO push      │
│  BenchmarkController  — /api/benchmark      │
└──────────────┬──────────────────────────────┘
               │  POST /worker/convert  (HTTP)
               │  { jobId, inputPath, outputPath,
               │    targetFormat, callbackBaseUrl }
               ▼
┌─────────────────────────────────────────────┐
│  Worker Service  —  port 3003               │
│                                             │
│  WorkerController  — POST /worker/convert   │
│  WorkerService     — ThreadPoolExecutor     │
│                      + idempotency guard    │
│  ApiCallbackClient — HTTP → API callbacks   │
│  ConversionTask    — actual conversion work │
│  ImageConverter / VideoConverter /          │
│  TextConverter                              │
└──────────────┬──────────────────────────────┘
               │  POST /api/internal/progress
               │  POST /api/internal/done
               │  POST /api/internal/failed
               ▼
        API Service  →  SocketService  →  Browser
```

The two processes share the filesystem (uploads/ and outputs/ directories). The API passes **absolute file paths** in the dispatch request so the Worker can locate files regardless of its working directory.

---

## Network Protocol (API ↔ Worker)

### API → Worker: dispatch a job
```
POST http://localhost:3003/worker/convert
Content-Type: application/json

{
  "jobId":           "550e8400-...",
  "inputPath":       "C:/project/uploads/550e8400-file.png",
  "outputPath":      "C:/project/outputs/550e8400.jpg",
  "originalName":    "photo.png",
  "targetFormat":    "jpg",
  "callbackBaseUrl": "http://localhost:3001"
}
```

Worker responses:
- `200` — accepted (queued for processing)
- `409` — duplicate jobId already active (idempotency guard)
- `503` — worker queue full (API marks job FAILED, returns HTTP 503 to browser)

### Worker → API: lifecycle callbacks
```
POST /api/internal/progress  { jobId, progress, message }
POST /api/internal/done      { jobId }
POST /api/internal/failed    { jobId, error }
```

The `jobId` appears in every log line in both processes and in every Socket.IO event — it is the **distributed correlation ID** that ties the full lifecycle of a job together.

---

## Idempotency

The Worker uses `ConcurrentHashMap.putIfAbsent(jobId, true)` as a lock-free idempotency guard. If the same jobId arrives twice (e.g., from an API retry), the second request is rejected with HTTP 409 and no duplicate conversion is started. The Worker removes the jobId from the map when the conversion finishes (success or failure), making it safe for the API to resubmit with the same jobId on a retry.

---

## Full Source Code Map

```
code/converter/
├── backend-java/                         ← API Service (ports 3001, 3002)
│   └── src/main/java/com/threadconv/
│       ├── ThreadConvApplication.java    — Spring Boot entry point
│       ├── model/
│       │   ├── JobStatus.java            — Enum: QUEUED, PROCESSING, COMPLETED, FAILED
│       │   └── Job.java                  — volatile fields for cross-thread visibility
│       ├── service/
│       │   ├── JobStoreService.java      — ConcurrentHashMap job registry
│       │   ├── WorkerPoolService.java    — HTTP dispatcher + metrics + cleanup cron
│       │   └── SocketService.java        — Socket.IO server (port 3002)
│       ├── client/
│       │   └── WorkerClient.java         — HTTP client: dispatch + fetchStats
│       └── controller/
│           ├── ApiController.java        — /api/upload, /api/jobs, /api/download, /api/stats
│           ├── InternalCallbackController.java  — /api/internal/progress|done|failed
│           └── BenchmarkController.java  — /api/benchmark?count=N
│
├── backend-worker/                       ← Worker Service (port 3003)
│   └── src/main/java/com/threadconv/worker/
│       ├── WorkerApplication.java        — Spring Boot entry point
│       ├── model/
│       │   └── WorkRequest.java          — Job dispatch payload (received from API)
│       ├── WorkerController.java         — /worker/convert, /worker/stats, /worker/health
│       ├── WorkerService.java            — ThreadPoolExecutor + idempotency + metrics
│       ├── ApiCallbackClient.java        — HTTP client: progress/done/failed → API
│       └── converter/
│           ├── ProgressCallback.java
│           ├── ConversionTask.java       — routes by ext, calls API callbacks
│           ├── ImageConverter.java       — FFmpeg, 2-min timeout
│           ├── VideoConverter.java       — FFmpeg, 30-min timeout, live progress
│           └── TextConverter.java        — pure Java
│
└── frontend/                             ← React UI (port 3000, unchanged)
```

---

## Request Flow (Distributed)

```
1. Browser       POST /api/upload
2. API           saves file, creates Job, calls WorkerClient.dispatch(job)
3. API → Worker  POST /worker/convert  { jobId, inputPath, outputPath, ... }
4. Worker        idempotency check → submits to ThreadPoolExecutor → returns 200
5. Worker thread starts ConversionTask.run()
6. Worker → API  POST /api/internal/progress  { jobId, 40, "Converting..." }
7. API           updates Job in JobStore, emits Socket.IO job:progress
8. Browser       progress bar updates live
9. Worker → API  POST /api/internal/done  { jobId }
10. API          job.status = COMPLETED, emits job:done, records latency
11. Browser      "Download" button appears
```

On failure: Worker posts `/api/internal/failed`. API increments attempt counter — if attempts < 3, resubmits to Worker; otherwise marks FAILED permanently and emits `job:failed`.

---

## Concurrency Design

### API Service
- **No local executor** — dispatches over HTTP; the network call itself is the only thread synchronisation point
- **AtomicLong counters** — totalCompleted, totalFailed, totalRejected (lock-free)
- **Synchronized deque** — `recentLatencies` (small contention: one write per completed job)
- **ConcurrentHashMap** — JobStoreService (lock-striped, reads never block)
- **volatile fields on Job** — status, progress, progressMessage, completedAt (worker writes, HTTP thread reads)

### Worker Service
- **ThreadPoolExecutor** — `corePoolSize == maxPoolSize` (cores - 1, min 2) so threads are created eagerly
- **LinkedBlockingQueue(200)** — bounded; AbortPolicy → HTTP 503 to API → HTTP 503 to browser
- **ConcurrentHashMap.putIfAbsent** — idempotency guard (lock-free, O(1))
- **Daemon threads** — worker threads don't prevent JVM shutdown

### Why `corePoolSize == maxPoolSize`
Java's `ThreadPoolExecutor` only creates threads beyond `corePoolSize` when the queue is **full**. With a queue of 200 and a core of 2, the pool would stay at 2 threads for the first 200 jobs — never scaling up. Setting core = max forces threads to be created as soon as jobs arrive.

---

## Benchmark Endpoint

```
GET /api/benchmark?count=8
```

Runs `count` SHA-256 hashes of a 16 MB synthetic buffer — first sequentially, then in parallel using `CompletableFuture` + a `FixedThreadPool(cores-1)`.

Example result (8-core machine):
```json
{
  "task":          "SHA-256 hash of 16 MB buffer",
  "count":         8,
  "threads":       7,
  "sequentialMs":  115,
  "parallelMs":    26,
  "speedupFactor": "4.4x"
}
```

Speedup ≈ number of cores — demonstrates that CPU-bound work scales linearly with parallelism for independent tasks.

---

## Ports

| Port | Process | Protocol | What |
|---|---|---|---|
| 3000 | Frontend | HTTP | React dev server |
| 3001 | API Service | HTTP | REST API |
| 3002 | API Service | WebSocket | Socket.IO live updates |
| 3003 | Worker Service | HTTP | Conversion engine |

---

## Configuration

**API** — `backend-java/src/main/resources/application.properties`:
```properties
server.port=3001
app.socket-port=3002
app.upload-dir=./uploads
app.output-dir=./outputs
app.ffmpeg-path=ffmpeg
app.worker-url=http://localhost:3003
app.api-callback-base-url=http://localhost:3001
```

**Worker** — `backend-worker/src/main/resources/application.properties`:
```properties
server.port=3003
app.api-base-url=http://localhost:3001
app.ffmpeg-path=ffmpeg
```

---

## Change Log

### Session 1 — Node.js → Java migration
- Rewrote entire backend in Java 21 + Spring Boot 3.3.4 + Gradle 8.10.2
- Replaced Node.js Express with Spring Boot REST (`ApiController`)
- Replaced Node.js Socket.IO server with `netty-socketio` (`SocketService`)
- Replaced Node.js worker threads with `ThreadPoolExecutor` + `LinkedBlockingQueue(500)`
- Ported all converters: `ImageConverter`, `VideoConverter`, `TextConverter`
- Added `Job` model with `volatile` fields for cross-thread visibility
- Added `JobStoreService` with `ConcurrentHashMap`
- Added retry logic (up to 3 attempts) in `WorkerPoolService`
- Added graceful shutdown (`@PreDestroy`, 60s drain)
- Added `/api/stats` endpoint (p50/p95/p99 latency, throughput, queue depth)
- Kept React frontend and `stress-advanced.js` unchanged

### Session 2 — Thread pool scaling fix
- Bug: `activeWorkers` stuck at 2 under load
- Root cause: `corePoolSize=2` with `LinkedBlockingQueue(500)` — Java only creates threads beyond core when queue is full
- Fix: set `corePoolSize = maxPoolSize = (cores - 1)` so threads are created eagerly

### Session 3 — Gradle wrapper, run scripts, repo cleanup
- Generated `gradlew.bat` (Gradle 8.10.2) — collaborators no longer need Gradle installed
- Created `run-backend.bat`, `run-frontend.bat`
- All scripts use `%~dp0` — work from any clone location
- Cleaned `.gitignore`: added `bin/`, `*.class`, `*.tmp`, `.idea/`, `*.iml`, `Thumbs.db`
- Deleted stale files: `code/howToRun.md`, `code/STRESS_TESTING.md`, `code/VIDEO_CONVERSION.md`, `code/start.bat`
- Created `ARCHITECTURE.md`, `STRESS-TESTING.md`

### Session 4 — Frontend theme & layout
- Full CSS rewrite: dark theme (`#0a0a0a` background, `#b91c1c` dark red accent, `#f0ede8` text)
- Layout changed from two-column to single-column (jobs panel below upload panel)
- Fixed CSS class name mismatch between `App.js` and old CSS — rewrote CSS to match actual class names
- Max width: 860px centered

### Session 5 — Distributed architecture (Section 4 requirement)
- Split into two separate Spring Boot processes: API Service (3001/3002) and Worker Service (3003)
- Created `backend-worker/` as a standalone Gradle project
- API dispatches jobs to Worker via `POST /worker/convert` (HTTP)
- Worker calls back API via `POST /api/internal/{progress|done|failed}`
- Added `WorkerClient.java` — HTTP client in API, dispatches and fetches stats
- Added `InternalCallbackController.java` — receives Worker callbacks, updates job store, emits Socket.IO
- Added idempotency guard in Worker (`ConcurrentHashMap.putIfAbsent`)
- `jobId` flows through all log lines and HTTP calls as distributed correlation ID
- `/api/stats` now includes a `worker` nested object with live Worker metrics
- Created `run-worker.bat`
- Documented the 3-terminal startup (Worker → API → Frontend) in `README.md`

### Session 5 (continued) — Sequential vs parallel benchmark
- Added `GET /api/benchmark?count=N` endpoint (`BenchmarkController.java`)
- Task: SHA-256 hash of 16 MB buffer — CPU-bound, no I/O, no FFmpeg dependency
- Runs task N times sequentially then N times in parallel (`CompletableFuture` + `FixedThreadPool`)
- Returns timing for both modes, per-run breakdown, and calculated speedup factor
- Measured result on dev machine: 115ms sequential → 26ms parallel → **4.4x speedup** with 7 threads

---

## What's Still the Same from the Original

| Component | Status |
|---|---|
| React frontend (`code/converter/frontend/`) | Unchanged except `socket.js` port number |
| REST API contract (URLs, JSON shapes) | Same — frontend didn't need to change |
| Socket.IO event names | Same: `job:processing`, `job:progress`, `job:done`, `job:failed`, `job:requeued` |
| `stress-advanced.js` | Unchanged — works against Java backend |
| FFmpeg usage | Same shell commands, same format support |
