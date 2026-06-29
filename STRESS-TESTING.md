# THREADCONV — Stress Testing

How to run the stress test, what it does, and how to read the results.

---

## Prerequisites

- Java backend is running (see `README.md`)
- Node.js is installed (`node -v` should work)
- You're in the `code/` directory when you run the test

---

## Running the Stress Test

```powershell
cd code
node stress-advanced.js
```

The test will print progress as it runs, then print a summary table when done.

---

## What the Stress Test Does

`stress-advanced.js` hammers the backend with concurrent file upload batches to verify:

1. **The thread pool handles concurrency correctly** — jobs don't corrupt each other
2. **Bounded queue works** — excess jobs get rejected cleanly (no crash)
3. **Success rate stays high** — failed conversions should be near 0%
4. **Throughput is reasonable** — jobs/second metric

It does this by:
1. Generating synthetic test files (small MP4 video, image, and text files in memory)
2. Uploading them in waves (multiple concurrent batches)
3. Polling `/api/jobs` until all jobs reach a terminal state (completed or failed)
4. Printing a summary

---

## Reading the Output

A typical successful run looks like:

```
THREADCONV Stress Test
======================
Files per batch : 10
Batch size      : 5
Upload waves    : 1
Total files     : 50

Uploading wave 1/1...
  Batch 1/5 submitted (10 jobs)
  Batch 2/5 submitted (10 jobs)
  ...

Waiting for all 50 jobs to complete...
  [████████████████████] 50/50 done

Results
-------
Completed   : 50 / 50
Failed      : 0
Success rate: 100.00%
Total time  : 8.16s
Throughput  : 6.1 jobs/sec
```

### What each number means

| Field | What it tells you |
|---|---|
| Completed | Jobs that converted successfully |
| Failed | Jobs that errored out (after all retries) |
| Success rate | Should be 100% under normal conditions |
| Total time | Wall-clock time from first upload to last completion |
| Throughput | Jobs finished per second |

---

## Adjusting the Test Parameters

At the top of `stress-advanced.js`, you can change:

```js
const CONFIG = {
  filesPerBatch: 10,   // files in each HTTP upload request
  numBatches: 5,       // number of parallel upload batches
  waves: 1,            // how many waves of batches to send
  // ...
};
```

Increasing these numbers increases load on the backend. The queue cap is 500 jobs — if you submit more than that at once, the extras will be rejected with a "queue full" error (this is intentional, not a bug).

---

## Checking Backend Metrics After a Run

While the backend is running (or after a stress test), hit the stats endpoint:

```
http://localhost:3001/api/stats
```

Or from the terminal:
```powershell
# PowerShell
(Invoke-WebRequest http://localhost:3001/api/stats).Content

# Or if you have curl
curl http://localhost:3001/api/stats
```

Example response:
```json
{
  "activeWorkers": 0,
  "maxWorkers": 7,
  "queueSize": 0,
  "totalCompleted": 50,
  "totalFailed": 0,
  "totalRejected": 0,
  "throughputPerMinute": 36.7,
  "p50LatencyMs": 156,
  "p95LatencyMs": 412,
  "p99LatencyMs": 891
}
```

### Metric glossary

| Metric | Meaning |
|---|---|
| `activeWorkers` | Threads currently converting a file |
| `maxWorkers` | Thread pool ceiling (= CPU cores − 1, minimum 2) |
| `queueSize` | Jobs waiting in the queue |
| `totalCompleted` | All-time successful conversions since backend started |
| `totalFailed` | Permanent failures (after 3 retry attempts) |
| `totalRejected` | Jobs rejected because the queue was full (HTTP 503) |
| `throughputPerMinute` | Completed jobs per minute (rolling since startup) |
| `p50LatencyMs` | Median job time in milliseconds |
| `p95LatencyMs` | 95th percentile — 95% of jobs finished faster than this |
| `p99LatencyMs` | 99th percentile — only 1% of jobs took longer than this |

---

## Troubleshooting Failed Jobs

If the stress test shows failed jobs (> 0), check the backend terminal output for error lines. Common causes:

| Symptom | Likely cause |
|---|---|
| `FFmpeg exited with code 1` | FFmpeg not on PATH, or unsupported conversion |
| `aac encoder experimental` | Old FFmpeg build (upgrade to a post-2016 build) |
| `queue full` | Too many jobs submitted at once — spread uploads out or increase queue cap |
| `timed out after 30 minutes` | Very large video file or very slow machine |
| `Unsupported video format` | Input format not in the supported list |
