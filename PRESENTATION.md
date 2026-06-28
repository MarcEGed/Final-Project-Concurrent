# THREADCONV — Presentation & Demo Script

**Format:** 3 speakers · ~6–7 minutes total + 2 min Q&A · Topic **B (ML / Data-Analysis Pipeline)**.
Every member speaks. Each section has **SAY** (spoken lines) and **SHOW** (what to do on screen).

---

## ⏱ Pre-flight (do this BEFORE you present)

Have **4 terminals** + a browser ready and arranged so the audience can see them:

1. Terminal 1 — `.\run-worker.bat`  (Worker, :3003) — already running
2. Terminal 2 — `.\run-backend.bat` (API, :3001/:3002) — already running, **keep visible** (logs scroll here)
3. Terminal 3 — `.\run-frontend.bat` (UI) — already running
4. Terminal 4 — sitting in `code/` with `JAVA_HOME` set, for the live commands:
   ```powershell
   $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
   cd code
   ```
- Browser tab A: **http://localhost:3000** (the app)
- Browser tab B: **http://localhost:3001/api/stats** (metrics — refresh on demand)
- Have a couple of small test files on the Desktop ready to drag in.

> If anything is stuck, run `node failure-injection.js` once beforehand to confirm both
> services are healthy, then `curl -X DELETE http://localhost:3001/api/jobs/clear` to reset.

---

# 👤 Speaker 1 — The problem, the design, concurrent vs parallel, live demo
*(~2 min 15s)*

### SAY — what it is
"Our topic is **B, a data-processing pipeline**. THREADCONV is a file-conversion
service: you upload images, videos or text in the browser, and it converts them.
It's a real three-stage pipeline — **pre-process** (validate and hash the upload),
**convert** (the CPU-heavy stage), and **post-process/store** (write the output and
notify you). It needs this course because many users upload at once, the conversions
are CPU-bound, and the system has to stay correct, bounded, and recoverable under load."

**SHOW:** the architecture diagram in [DIAGRAMS.md](DIAGRAMS.md) (or a slide of it). Point to the two boxes.

### SAY — concurrent vs parallel (the key course idea)
"Two words people mix up — and our system uses both:
- **Concurrency** is *structure*: dealing with many jobs in flight at once. Our job
  queue and thread pool interleave hundreds of jobs — that's concurrency.
- **Parallelism** is *execution*: actually doing work at the same instant on multiple
  CPU cores. Each conversion is CPU-bound, so we run them on a pool of worker threads
  truly in parallel.
So: **the queue gives us concurrency; the multi-core worker pool gives us parallelism.**
Later my teammate will show a benchmark that measures the parallel speedup."

**SHOW:** point at the diagram — the single queue (concurrency) feeding the 7 worker threads (parallelism).

### SAY — live demo (happy path)
"Here it is running. I'll drop a few files and convert them."

**SHOW (in browser tab A):**
1. Drag 3–4 files into the dropzone, pick a target format (e.g. Image → **.png**, or Video → **.mp4**).
2. Click **CONVERT**.
3. Point at the **live progress bars** and the **"Live" dot** top-right: "these updates
   are pushed over a WebSocket in real time — Socket.IO on a separate port."
4. When they hit **DONE**, click a **download** arrow to show a converted file opens.

"That real-time progress is the system streaming each job's state back as it happens.
Now my teammate will open the hood and show *how* it stays correct under load."

**Course tie-in to mention:** Weeks 1–2 (threads & when to add concurrency), Week 7 (async pipeline + real-time updates).

---

# 👤 Speaker 2 — Threads, bounded queue, shared state, idempotency, stress + benchmark
*(~2 min 30s)*

### SAY — threads, pool, bounded queue (Week 5)
"The conversion work runs in a **ThreadPoolExecutor** sized to **cores − 1**, here 7
threads, fed by a **bounded queue of 200**. Bounded is the important word — the course
is clear that **unbounded resources are not allowed**. If 7 threads are busy and 200
jobs are already waiting, the 208th is **rejected** with an HTTP 503 instead of growing
memory forever. That's **backpressure**."

**SHOW:** [CONCURRENCY-SCORECARD.md](CONCURRENCY-SCORECARD.md) — the sizing-rationale section, or the `WorkerService` constructor in code.

### SAY — shared state & how it's protected (Weeks 3–4)
"Whenever you have threads, you have **shared mutable state** and **race conditions**.
We list every piece and how it's protected:
- the **job registry** is a `ConcurrentHashMap` — lock-striped, no single global lock;
- each job's **status and progress** are `volatile`, so a write on a worker thread is
  immediately **visible** to the HTTP thread that reads it — that's the happens-before
  guarantee from Week 4;
- the **counters** are `AtomicLong`, lock-free.
We deliberately avoid nested locks, so the design is **deadlock-free**."

**SHOW:** the shared-state table in [CONCURRENCY-SCORECARD.md](CONCURRENCY-SCORECARD.md).

### SAY — idempotency (Weeks 10–14)
"Because we retry across a network, **retries must not duplicate work**. Two guards:
first, an upload is **content-hashed** — the same file converted to the same format is
**deduplicated** instead of re-run. Second, the worker uses an atomic `putIfAbsent` on
the jobId, so the same job arriving twice is rejected with a 409 — no double conversion."

**SHOW (live):** drag the **same file twice** and convert; point at the API terminal log line
`[Upload] Duplicate detected — reusing job ...`. "Same result, no wasted work."

### SAY — stress test + metrics + benchmark (Weeks 6–7)
"Correctness has to hold **under load**, so we stress it."

**SHOW (Terminal 4):**
```powershell
node stress-advanced.js
```
"This fires **50 unique videos** through the pool concurrently." When it finishes:
"**50 of 50, 100% success.**" Then open **stats tab B** / run `curl http://localhost:3001/api/stats`:
"latency percentiles — **p50, p95, p99** — plus throughput and queue depth, exactly the
metrics the course asks for."

Then the benchmark:
```powershell
curl "http://localhost:3001/api/benchmark?count=8"
```
"This is the **sequential-vs-parallel** measurement for our CPU-bound step — hashing a
16 MB buffer. Sequential ~150 ms, parallel ~40 ms: about **3.9× speedup** on 7 threads.
That's the **parallelism** my teammate mentioned, measured. It's also the Week 6 idea of
**break-even** — parallelism only pays off because each task is big enough."

**Course tie-in:** Week 5 (executor, bounded queue, atomics), Week 6 (parallel speedup, break-even), Week 7 (stress testing).

---

# 👤 Speaker 3 — Distributed boundary, failure injection, recovery, tradeoff
*(~2 min 15s)*

### SAY — the network boundary (Weeks 10–14)
"This isn't one process — it's **two**. The **API** and the **Worker** are separate
services that talk over **HTTP**, with JSON serialization, timeouts, and logging. That's
our real distributed boundary. The **jobId** is the **correlation ID** — it appears in
every API log line, every Worker log line, and every WebSocket event, so we can trace one
job across both processes."

**SHOW:** the failure-propagation diagram in [DIAGRAMS.md](DIAGRAMS.md).

### SAY — failure injection #1: kill the worker (timeout, fallback, recovery)
"The course says: prove it survives failure. We inject two. First, I'll **kill the worker
mid-flight** and upload anyway."

**SHOW (Terminal 4):**
```powershell
node failure-injection.js A
```
Narrate as it runs: "It kills the worker process… uploads a job… the API's dispatch
**times out and fails fast** — the job is marked FAILED with a clear message instead of
hanging. Then it **restarts the worker**, uploads again, and that job **completes**. So:
**timeout → fallback → recovery.**"

**SHOW:** point at the API terminal — the `Worker unreachable` line, then the later
`PROGRESS … DONE` line for the recovered job. "Same jobId traced through the failure."

### SAY — failure injection #2: overload the bounded queue (backpressure)
"Second failure — **overload**. We fire a burst of **240** jobs at a system that can only
hold **207**."

**SHOW (Terminal 4):**
```powershell
node failure-injection.js B
```
"Result: **exactly 207 accepted, 33 rejected** with 503. The boundary is **deterministic** —
the system sheds load on purpose and stays up, instead of falling over. Overload behaviour
is **intentional and measurable**, which is exactly what the assignment demands."

### SAY — the one tradeoff
"Our key tradeoff: we chose a **bounded queue with reject-on-full** over an unbounded
queue. The cost is that under extreme load we **drop** some requests. The benefit is we can
**never run out of memory** and overload is **visible** as a metric. And because we have
idempotency, a client can safely **retry** a rejected request. We wrote this up as a formal
decision memo."

**SHOW:** [DECISION-MEMO.md](DECISION-MEMO.md) (Q1–Q5) briefly.

### SAY — close
"To sum up: a real concurrent **pipeline** — concurrency in the queue, parallelism on the
cores — that stays **correct** with safe shared state, **bounded** under load, **observable**
through metrics and tracing, and **recoverable** from failure. That's the whole point of the
course. Questions?"

**Course tie-in:** Weeks 10–14 (network boundary, serialization, idempotency, tracing, recovery), plus the concurrency scorecard.

---

## 🧩 Likely questions (be ready)

- **"Why core = max pool size?"** A `ThreadPoolExecutor` only adds threads past the core
  size once the queue is full; with a 200 queue it'd stay at the core size and never
  parallelise. core = max forces eager thread creation. (In [ARCHITECTURE.md](ARCHITECTURE.md).)
- **"Why 7 threads?"** Conversions are CPU-bound, so ≈ number of cores; we leave one core
  for the JVM, Tomcat I/O, and the OS.
- **"Is `attempts++` a race?"** It's `volatile` and only incremented on the per-job failure
  callback, which is serial per jobId (idempotency guard ⇒ one active execution per job), so
  no concurrent writers.
- **"Why HTTP and not Kafka?"** The core demo must run locally with no extra infra; HTTP gives
  us a real network boundary with timeouts and serialization. A broker is the documented next step.
- **"Where's the deadlock risk?"** Almost everything is lock-free; the only `synchronized` block
  is a tiny latency-recording critical section that acquires no other lock.

## ✅ One-line role split
- **Speaker 1:** topic + architecture + concurrent vs parallel + live demo.
- **Speaker 2:** thread pool + bounded queue + shared state + idempotency + stress test + benchmark.
- **Speaker 3:** distributed boundary + two failure injections + tradeoff + close.
