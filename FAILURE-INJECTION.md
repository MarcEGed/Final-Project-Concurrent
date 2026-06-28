# THREADCONV — Failure Injection

Two reproducible injected-failure scenarios, the commands to run them, and the
captured evidence. These satisfy the project requirements for *timeout & fallback*,
*bounded resources / backpressure*, *failure recovery*, and *two injected failure
scenarios*.

All injection is driven by one script: [`code/failure-injection.js`](code/failure-injection.js).

```powershell
# from the repo root, with API (:3001) and Worker (:3003) running
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
cd code
node failure-injection.js        # runs both scenarios
node failure-injection.js A      # scenario A only
node failure-injection.js B      # scenario B only
```

The script writes a transcript to [`evidence/failure-injection-output.txt`](evidence/).

---

## Scenario A — Worker process kill → fallback → recovery

**What is injected:** the Worker process (port 3003) is killed mid-operation with
`taskkill`, a job is uploaded while it is down, then the Worker is restarted.

**What it proves:** the API↔Worker network boundary handles a dead downstream
**without hanging or crashing**. `WorkerClient.dispatch()` fails fast inside its
connect/request timeout, the API takes the **fallback path** and marks the job
`FAILED` with a clear message, and counts it under `totalRejected`. After the
Worker is restarted the next job **completes** — the system **recovers**.

**Mechanism:** `HttpClient` connect timeout (5s) + request timeout (10s);
`WorkerClient` catches the failure → `DispatchResult.WORKER_UNREACHABLE` →
`WorkerPoolService.submit()` sets `JobStatus.FAILED` + increments `totalRejected`.

### Evidence (terminal)
```
[2] Killing the Worker process...
    taskkill PID 13584 → worker is now DOWN
    Worker health after kill: DOWN (as expected)

[3] Uploading a job while the Worker is DOWN (expect fallback → FAILED)...
    Upload HTTP response job: {"status":"failed","originalName":"fi-A-down.mp4","jobId":"0664716b-..."}
    Job state: status=failed  error="Worker service is unavailable. Make sure run-worker.bat is running."
    Stats: totalRejected=1  (fallback counted as rejected)

[4] Restarting the Worker...
    Worker health after restart: OK (recovered)

[5] Uploading another job after recovery (expect COMPLETED)...
    Job state: status=completed  (039fa3f1-...)

  RESULT: failed-while-down then recovered-after-restart — timeout/fallback + recovery proven
```

### Evidence (API log — distributed trace by `jobId` correlation ID)
Full log in [`evidence/scenario-A-api-log.txt`](evidence/scenario-A-api-log.txt).
```
--- FAILED request (worker down) ---
[API] Dispatching jobId=0664716b-... to http://localhost:3003
[API] Worker unreachable for jobId=0664716b-...: null

--- RECOVERED / SUCCESSFUL request (after restart) ---
[API] Dispatching jobId=039fa3f1-... to http://localhost:3003
[API] PROGRESS jobId=039fa3f1-...    5%  Preparing video conversion...
[API] PROGRESS jobId=039fa3f1-...  100%  Video conversion complete
[API] DONE     jobId=039fa3f1-...
```

---

## Scenario B — Bounded-queue overload → 503 backpressure

**What is injected:** a single concurrent burst of **240 unique** conversion
requests is fired at the API at once.

**What it proves:** resources are **bounded** and overload behaviour is
**intentional and measurable**. The Worker pool is 7 threads + a
`LinkedBlockingQueue(200)` = **207 jobs in flight**. The 33 requests beyond that
are rejected by the `AbortPolicy` with **HTTP 503**; the API marks them `FAILED`
("queue is full") and increments `totalRejected`. Nothing is unbounded; nothing
crashes.

**Mechanism:** `ThreadPoolExecutor(7,7,…, LinkedBlockingQueue(200), AbortPolicy)`
in `WorkerService` → 503 → `WorkerClient` returns `QUEUE_FULL` →
`WorkerPoolService` marks the job `FAILED` + `totalRejected++`.

### Evidence (terminal)
```
 Worker capacity = 7 threads + queue(200) = 207 in flight; beyond that → 503

[1] Generating 240 unique tiny videos...
[2] Firing all 240 uploads concurrently (single burst)...

  RESULT:
    burst submitted        : 240
    accepted (queued/done) : 207
    rejected at upload     : 33
    totalRejected (stats)  : 1 → 34  (Δ 33)
    queue overflowed and rejected excess with 503 — bounded resources proven
```

The accepted count (**207**) matches the configured capacity (7 + 200) exactly —
the backpressure boundary is precise and deterministic.

---

## How these map to the concurrency requirements

| Requirement | Demonstrated by |
|---|---|
| Timeout + fallback path | Scenario A — dispatch timeout → `WORKER_UNREACHABLE` fallback |
| Failure recovery path | Scenario A — job completes after Worker restart |
| Bounded resources (no unbounded queues/threads) | Scenario B — 207 cap, 503 beyond |
| Intentional, measurable overload | Scenario B — `totalRejected` delta = 33 |
| Distributed tracing across components | `jobId` correlation ID in every API + Worker log line |
| Retry of transient failures | `InternalCallbackController` retries a failed conversion up to 3× before permanent `FAILED` |
