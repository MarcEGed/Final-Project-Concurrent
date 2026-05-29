# THREADCONV — Architecture & Migration Notes

What the project is, what changed from the original, what stayed the same, and how all the pieces fit together.

---

## What the Project Does

THREADCONV is a multithreaded file conversion web app. Users upload files through a browser, the backend converts them concurrently using a managed thread pool, and live progress is streamed back via Socket.IO. Supported conversions:

| Input type | Supported formats |
|---|---|
| Images (jpg, png, webp, gif, tiff…) | jpg, png, webp, gif, tiff, avif |
| Video (mp4, mkv, avi, mov, webm…) | mp4, mkv, avi, mov, webm |
| Text (txt, md, json, html, csv, xml) | txt, md, json, html |

---

## What Changed: Node.js → Java

The project was originally written in Node.js (Express). We switched the **entire backend** to **Java 21 + Spring Boot 3 + Gradle** to meet the professor's requirements. The React frontend is almost completely unchanged.

### Why Java was required

The assignment requires:
- Java 21 as the primary language
- Gradle as the build system
- Specific Java concurrency primitives (ThreadPoolExecutor, ConcurrentHashMap, volatile, etc.)

Node.js's single-threaded event loop cannot demonstrate these primitives in the way the rubric expects.

---

## What Stayed the Same

| Component | Status | Notes |
|---|---|---|
| React frontend (`code/converter/frontend/`) | **Unchanged** | Same components, same UI, same npm deps |
| REST API contract | **Same endpoints** | Same URLs, same JSON shapes — frontend didn't need to know |
| Socket.IO event names | **Same names** | `job:processing`, `job:progress`, `job:done`, `job:failed`, `job:requeued` |
| `stress-advanced.js` | **Unchanged** | Works against the Java backend with no modifications |
| FFmpeg usage | **Same commands** | Backend still shells out to system FFmpeg |

**The only frontend file that changed** is `socket.js` — one line, port number only:
```js
// Before (Node.js backend used port 3001 for everything)
const socket = io('http://localhost:3001', ...)

// After (Java backend uses 3002 for Socket.IO, 3001 for REST)
const socket = io('http://localhost:3002', ...)
```

---

## What's New (Java Backend)

### Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 21 |
| Build system | Gradle 8 |
| Web framework | Spring Boot 3.3 |
| Socket.IO server | netty-socketio 2.0.6 |
| Thread pool | `ThreadPoolExecutor` (Java standard library) |
| JSON | Jackson (bundled with Spring) |
| Image/video conversion | FFmpeg via `ProcessBuilder` |
| Text conversion | Pure Java `java.nio.file` |

### Source Code Map

```
backend-java/
└── src/main/java/com/threadconv/
    ├── ThreadConvApplication.java    — Spring Boot entry point (@SpringBootApplication)
    ├── model/
    │   ├── JobStatus.java            — Enum: QUEUED, PROCESSING, COMPLETED, FAILED
    │   └── Job.java                  — Job data class (volatile fields for thread safety)
    ├── service/
    │   ├── JobStoreService.java      — ConcurrentHashMap job registry
    │   ├── WorkerPoolService.java    — ThreadPoolExecutor + bounded queue + metrics
    │   └── SocketService.java        — Socket.IO server (port 3002)
    ├── converter/
    │   ├── ProgressCallback.java     — @FunctionalInterface for progress reporting
    │   ├── ConversionTask.java       — Runnable submitted per job; routes by file type
    │   ├── ImageConverter.java       — FFmpeg image conversion (2 min timeout)
    │   ├── VideoConverter.java       — FFmpeg video conversion (30 min timeout, live progress)
    │   └── TextConverter.java        — Pure Java text conversion
    └── controller/
        └── ApiController.java        — All REST endpoints (/api/upload, /api/jobs, etc.)
```

---

## How a Conversion Works (Request Flow)

```
Browser
  │
  │  POST /api/upload (multipart, up to 500 MB per file)
  ▼
ApiController                        ← Spring HTTP thread
  │  saves file to ./uploads/
  │  creates Job object
  │  calls workerPool.submit(job)
  ▼
WorkerPoolService
  │  puts ConversionTask into LinkedBlockingQueue(500)
  │  if queue full → job marked FAILED, HTTP 503 returned
  ▼
ThreadPoolExecutor worker thread     ← background thread
  │  ConversionTask.run()
  │  routes to ImageConverter / VideoConverter / TextConverter
  │  calls progress.report() as work proceeds
  │         │
  │         ▼
  │   SocketService.emitProgress()   ← pushes to all connected browsers
  │
  │  on success: job.status = COMPLETED, emitDone()
  │  on failure: retry up to 3× then job.status = FAILED, emitFailed()
  ▼
Browser receives Socket.IO events
  - job:processing
  - job:progress (0–100 %)
  - job:done  OR  job:failed
```

---

## Concurrency Design (Why It's Built This Way)

### ThreadPoolExecutor with bounded queue

```java
new ThreadPoolExecutor(
    2,          // 2 threads always alive (core pool)
    cpus - 1,   // scales up under load, leaves 1 core for HTTP threads
    60s,        // idle threads above core size die after 60 s
    new LinkedBlockingQueue<>(500),   // hard cap — no unbounded resource growth
    new AbortPolicy()                 // throws instead of silently dropping
);
```

The bounded queue is the key: without it, submitting 10,000 jobs would allocate 10,000 Runnables and crash with OOM. With it, jobs beyond 500 get a clean rejection (HTTP 503) instead.

### volatile fields on Job

```java
public class Job {
    private volatile JobStatus status;
    private volatile int progress;
    private volatile String progressMessage;
    // ...
}
```

`volatile` guarantees that writes from a worker thread are immediately visible to the HTTP thread reading `/api/jobs/{id}` — without the overhead of `synchronized`. This is safe because each field is written by exactly one thread at a time (the worker that owns the job).

### ConcurrentHashMap in JobStoreService

```java
private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();
```

`ConcurrentHashMap` uses internal lock-striping: reads never block each other, and writes only lock the relevant bucket. This means polling `/api/jobs` while 50 conversions are running has no contention.

### Retry logic (up to 3 attempts)

If a conversion fails (FFmpeg crash, disk error, etc.), the job is automatically re-queued up to 3 times before being marked permanently FAILED. The frontend sees `job:requeued` events so the UI stays accurate.

### Graceful shutdown

```java
@PreDestroy
public void shutdown() {
    executor.shutdown();                         // no new tasks accepted
    executor.awaitTermination(60, TimeUnit.SECONDS);  // finish in-progress work
    executor.shutdownNow();                      // force-kill if still running after 60 s
}
```

Pressing Ctrl+C gives running conversions 60 seconds to finish rather than cutting them off mid-file.

---

## Ports

| Port | What | Protocol |
|---|---|---|
| 3000 | React frontend (dev server) | HTTP |
| 3001 | Java backend REST API | HTTP |
| 3002 | Java backend Socket.IO | WebSocket |

---

## Configuration

All tunable values are in `backend-java/src/main/resources/application.properties`:

```properties
server.port=3001
spring.servlet.multipart.max-file-size=500MB
spring.servlet.multipart.max-request-size=10000MB

app.upload-dir=./uploads
app.output-dir=./outputs
app.socket-port=3002
app.ffmpeg-path=ffmpeg          # change to absolute path if ffmpeg isn't on PATH
```

---

## The Old Backend

`code/converter/backend/` still exists but is **not used**. It's the original Node.js implementation. You can ignore it — don't run it, and don't modify it. The Java backend at `code/converter/backend-java/` replaced it entirely.
