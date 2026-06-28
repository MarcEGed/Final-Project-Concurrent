# Architecture Decision Memo

**Decision:** Bound the conversion worker with a fixed thread pool + a fixed-size
queue and an **AbortPolicy** (reject-and-signal) instead of an unbounded queue or
a caller-blocking policy.

`new ThreadPoolExecutor(7, 7, 0L, MS, new LinkedBlockingQueue<>(200), AbortPolicy)`
in `WorkerService`, with the API translating a rejection into HTTP 503 and the
browser into a clear "queue full, try again" message.

---

### Q1 — What guarantee does this design provide?

**Bounded resource use and fast, predictable overload behaviour.** At most
`7 + 200 = 207` conversion jobs exist in the worker at any instant. Memory, file
handles, and FFmpeg subprocesses are therefore bounded regardless of input rate.
A submitter always gets one of three immediate outcomes — *queued*, *running*, or
*rejected (503)* — never an unbounded wait and never silent memory growth.

### Q2 — What failure modes does it prevent?

- **Out-of-memory / unbounded queue growth** under a burst or a stuck downstream —
  the classic failure of `Executors.newCachedThreadPool()` or an unbounded
  `LinkedBlockingQueue`.
- **Thread explosion / CPU thrashing** from unbounded thread creation on a
  CPU-bound workload.
- **Invisible overload:** because rejection is explicit (503 + `totalRejected`
  metric), overload is observable instead of manifesting as creeping latency.

### Q3 — What failure modes does it introduce?

- **Rejected work:** once full, valid requests are dropped (503). The client must
  retry/back off; we do not transparently buffer them. (Mitigated by idempotency —
  a retried request with the same jobId is safe.)
- **A too-small queue can reject prematurely**; a too-large one delays the
  backpressure signal and inflates tail latency. The 200 figure is a tuned
  trade-off, not a universal constant.
- **Head-of-line latency:** a slow large video occupies a thread and can delay
  queued small jobs (no priority lanes).

### Q4 — How does it behave under overload? (measured)

Single concurrent burst of **240** unique conversions
([evidence/load-test.md](evidence/load-test.md), [FAILURE-INJECTION.md](FAILURE-INJECTION.md)):

| Submitted | Accepted (7 + 200) | Rejected (503) | `totalRejected` Δ |
|---|---|---|---|
| 240 | **207** | **33** | +33 |

Accepted equals capacity exactly — the boundary is deterministic. Normal load
(50 concurrent) runs at 100% success, p50 2556 ms / p95 4664 ms / p99 4838 ms.

### Q5 — How would a new engineer debug it?

1. **`GET /api/stats`** — `queueSize`, `activeWorkers/maxWorkers`,
   `totalCompleted/Failed/Rejected`, p50/p95/p99. Rising `totalRejected` = hitting
   the cap; rising `queueSize` = saturation building.
2. **Follow a `jobId`** — the correlation ID appears in every API log line, every
   Worker log line, and every Socket.IO event, so one job's whole cross-process
   lifecycle greps cleanly.
3. **Reproduce** — `node code/failure-injection.js B` to force overload,
   `... A` to force the worker-down path.
4. **Tune** — `MAX_QUEUE_CAPACITY` in `WorkerService` and the pool size; re-run the
   load test to see the new break-even.

---

**Alternatives considered:** `CallerRunsPolicy` (rejected — would block Tomcat
request threads and stall the API under load); unbounded queue (rejected —
violates the "no unbounded resources" requirement and risks OOM); a broker
(Kafka/Redis) for durable buffering (deferred — heavier ops, and the assignment’s
core demo must run locally without extra infrastructure).
