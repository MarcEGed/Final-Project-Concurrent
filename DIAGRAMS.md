# THREADCONV — Diagrams

Architecture, async sequence, and failure-propagation diagrams (Mermaid — renders
on GitHub).

---

## 1. Architecture

```mermaid
flowchart TB
    Browser["Browser — React UI :3000"]

    subgraph API["API Service  :3001 REST / :3002 WebSocket"]
        AC["ApiController<br/>/api/upload · /jobs · /download · /stats"]
        JS["JobStoreService<br/>ConcurrentHashMap + dedup index"]
        WPS["WorkerPoolService<br/>AtomicLong metrics · retry · cleanup"]
        WC["WorkerClient<br/>HTTP dispatch (timeouts)"]
        ICC["InternalCallbackController<br/>/api/internal/progress|done|failed"]
        SS["SocketService<br/>Socket.IO push"]
        BC["BenchmarkController<br/>seq vs parallel"]
    end

    subgraph WK["Worker Service  :3003"]
        WCtl["WorkerController<br/>/worker/convert · /stats · /health"]
        WS["WorkerService<br/>ThreadPoolExecutor(7,7) + Queue(200)<br/>AbortPolicy · idempotency guard"]
        CT["ConversionTask → Image/Video/Text converters<br/>(FFmpeg, timeouts)"]
        ACB["ApiCallbackClient<br/>HTTP → API callbacks"]
    end

    FS[("Shared filesystem<br/>uploads/ · outputs/")]

    Browser -- "POST /api/upload (multipart)" --> AC
    SS -- "live job:* events" --> Browser
    AC --> JS
    AC --> WPS --> WC
    WC -- "POST /worker/convert {jobId,paths,fmt,callbackUrl}" --> WCtl
    WCtl --> WS --> CT --> ACB
    ACB -- "POST /api/internal/{progress,done,failed}" --> ICC
    ICC --> JS
    ICC --> SS
    ICC -- "retry (<3)" --> WPS
    AC -. "write upload" .-> FS
    CT -. "read input / write output" .-> FS
    AC -. "read output for download" .-> FS
```

---

## 2. Async sequence (happy path)

```mermaid
sequenceDiagram
    autonumber
    participant B as Browser
    participant A as API (:3001)
    participant W as Worker (:3003)
    participant T as Worker thread
    participant S as Socket.IO (:3002)

    B->>A: POST /api/upload (file, targetFormat)
    A->>A: validate, hash, create Job (QUEUED)
    A->>W: POST /worker/convert {jobId, paths, callbackUrl}
    W->>W: putIfAbsent(jobId) — idempotency
    W-->>A: 200 ACCEPTED
    A-->>B: 200 {jobId, status: queued}
    W->>T: executor.submit(ConversionTask)
    T->>A: POST /api/internal/progress {40%}
    A->>S: emit job:progress
    S-->>B: progress bar updates
    T->>A: POST /api/internal/done {jobId}
    A->>A: status=COMPLETED, record latency
    A->>S: emit job:done
    S-->>B: "Download" appears
    B->>A: GET /api/download/{jobId}
    A-->>B: converted file
```

---

## 3. Failure propagation

```mermaid
flowchart TD
    U["Upload → API dispatch"] --> D{"Worker reachable<br/>within timeout?"}

    D -- "No (Scenario A: worker down)" --> UN["WorkerClient → WORKER_UNREACHABLE"]
    UN --> F1["Job = FAILED<br/>totalRejected++<br/>clear message to user"]
    F1 --> R["Worker restarts → next upload COMPLETES (recovery)"]

    D -- "Yes" --> Q{"Worker queue<br/>(7 + 200) full?"}
    Q -- "Yes (Scenario B: overload)" --> AB["AbortPolicy → HTTP 503"]
    AB --> F2["Job = FAILED 'queue full'<br/>totalRejected++ (backpressure)"]

    Q -- "No" --> RUN["ConversionTask runs"]
    RUN --> C{"Conversion ok?"}
    C -- "Yes" --> DONE["/internal/done → COMPLETED"]
    C -- "No (FFmpeg error / timeout)" --> FAIL["/internal/failed"]
    FAIL --> AT{"attempts < 3?"}
    AT -- "Yes" --> RQ["requeue + resubmit (idempotent)"] --> RUN
    AT -- "No" --> PERM["Job = FAILED (permanent)<br/>totalFailed++"]

    classDef bad fill:#3a0d0d,stroke:#b91c1c,color:#f5d5d5;
    classDef good fill:#0d2a12,stroke:#1f9d4d,color:#cdeccd;
    class F1,F2,PERM,AB,UN,FAIL bad;
    class DONE,R good;
```
