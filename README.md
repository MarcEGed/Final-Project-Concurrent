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

Start Worker → API → Frontend. Full detail in [SETUP.md](SETUP.md) and
[ARCHITECTURE.md](ARCHITECTURE.md).

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

## 📚 Submission artifacts

| Document | What |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | System design, components, ports, change log |
| [DIAGRAMS.md](DIAGRAMS.md) | Architecture, async sequence, failure-propagation diagrams |
| [CONCURRENCY-SCORECARD.md](CONCURRENCY-SCORECARD.md) | Scorecard + shared-state inventory + sizing rationale |
| [DECISION-MEMO.md](DECISION-MEMO.md) | Architecture decision memo (Q1–Q5) |
| [FAILURE-INJECTION.md](FAILURE-INJECTION.md) | Two injected failure scenarios + how to reproduce |
| [PRESENTATION.md](PRESENTATION.md) | 3-speaker demo script (say/show cues, course tie-ins) |
| [STRESS-TESTING.md](STRESS-TESTING.md) | Load/stress test guide |
| [evidence/](evidence/) | Captured failure-injection output, API logs, load-test table |
| [SETUP.md](SETUP.md) · [QUICKSTART.md](QUICKSTART.md) · [COMMANDS.md](COMMANDS.md) | Setup & run references |

---

# 🚀 Features

## 📤 Bulk File Upload
- Drag & drop file upload
- Multiple file selection
- Chunked uploads for large files
- Upload queue system
- File validation (type + size limits)

**Implementation**
- React frontend
- Express.js backend
- multer for file handling
- Local storage (dev) / S3 (prod)

---

## 🔄 File Conversion Engine
- Image conversion (JPG, PNG, WEBP)
- Document conversion (PDF, DOCX, TXT)
- Audio conversion (MP3, WAV)
- Video conversion (MP4, MKV)

**Implementation**
- Node.js worker_threads
- sharp → image processing
- ffmpeg → video/audio conversion
- pdf-lib → document handling
- fs module → file system operations

---

## 🧵 Multithreading & Concurrency
- Worker thread pool execution
- Parallel file processing
- Controlled concurrency (max workers limit)
- Job queue system
- Retry mechanism for failures
- Backpressure handling

**Implementation**
- Node.js worker_threads
- Optional Redis + BullMQ queue
- workerPool.js → manages threads
- worker.js → executes tasks
- jobQueue.js → manages tasks

---

## 📊 Real-Time Updates
- Live upload progress
- Live conversion progress
- Job status tracking:
  - queued
  - processing
  - completed
  - failed

**Implementation**
- Socket.IO WebSockets
- Server emits events:
  - job:queued
  - job:progress
  - job:done
  - job:failed

---

## 📁 File Management
- Download converted files
- Batch download as ZIP
- Temporary file cleanup
- Conversion history tracking

**Implementation**
- archiver (ZIP creation)
- cron job cleanup system
- Redis or DB metadata storage

---

## 🔐 Authentication (Optional)
- Guest mode available
- User accounts:
  - conversion history
  - higher upload limits

**Implementation**
- JWT authentication
- bcrypt password hashing
- MongoDB / PostgreSQL

---

# 🏗️ System Architecture (FULL BULLET POINT DESIGN)

## 🌐 Overall System Flow
- User uploads files via frontend
- Backend receives files via API
- Files are validated and stored temporarily
- Jobs are created for each file
- Jobs are pushed into queue
- Worker threads pick up jobs
- Conversion engine processes files
- Output stored in storage layer
- User downloads results via API

---

## 🧩 System Components

### 🖥️ Frontend Layer
- React application
- Handles UI rendering
- Upload interface (drag & drop)
- Progress dashboard
- Download interface
- Communicates with backend via REST + WebSockets

---

### ⚙️ Backend API Layer
- Express.js server
- Handles HTTP requests
- Upload endpoints
- Job creation endpoints
- File download endpoints
- Auth endpoints (optional)
- Communicates with:
  - queue system
  - worker pool
  - socket server

---

### 📦 Job Queue System
- Stores conversion tasks
- Ensures ordered execution
- Handles task prioritization
- Prevents overload of workers
- Supports retry scheduling

**Implementation options**
- Redis + BullMQ (production)
- In-memory queue (development)

---

### 🧵 Worker Thread Pool
- Executes CPU-heavy conversions
- Runs in parallel threads
- Each worker processes one job at a time
- Thread pool limits concurrency
- Balances system load

---

### 🔄 Conversion Engine
- Performs actual file transformations
- Uses specialized libraries:
  - sharp → images
  - ffmpeg → audio/video
  - pdf-lib → documents
- Runs inside worker threads

---

### 💾 Storage Layer
- Stores uploaded files
- Stores converted output files
- Handles temporary file cleanup

**Options**
- Local filesystem (dev)
- AWS S3 (production)
- MinIO (self-hosted cloud storage)

---

### 📡 Real-Time Communication Layer
- WebSocket system (Socket.IO)
- Sends live updates to frontend
- Streams job progress in real time
- Sends completion/failure events

---

## 🔁 Full System Flow (STEP-BY-STEP)

- Step 1: User uploads files
- Step 2: Frontend sends files to API
- Step 3: Backend validates files
- Step 4: Backend creates jobs per file
- Step 5: Jobs are pushed to queue
- Step 6: Worker pool pulls job
- Step 7: Worker processes file conversion
- Step 8: Output saved to storage
- Step 9: Backend notifies frontend via WebSocket
- Step 10: User downloads converted file

---

## 🧠 Concurrency Model

### Producer–Consumer Pattern
- Producer:
  - uploadController.js
  - creates jobs
- Consumer:
  - workerPool.js
  - worker.js processes jobs
- Queue:
  - jobQueue.js

---

### Thread Pool Model
- Main thread:
  - handles API requests
  - manages job queue
- Worker threads:
  - execute conversions in parallel
  - isolated execution per task

---

### Synchronization Model
- Job queue ensures safe shared access
- Workers do not share memory state
- Prevents race conditions
- Maintains consistent job state

---

## 🧠 Backend Modules

- server.js
  - Express server setup
  - API routing
  - Socket.IO initialization

- uploadController.js
  - handles file uploads
  - validates files
  - creates jobs

- jobQueue.js
  - manages job lifecycle
  - queues tasks
  - handles retries

- workerPool.js
  - manages worker threads
  - assigns jobs
  - load balancing

- worker.js
  - executes conversions
  - CPU-heavy processing

- socketService.js
  - emits real-time updates

---

## 🖥️ Frontend Architecture

- components/
  - UploadZone
  - FileQueue
  - ProgressBar
  - Dashboard

- pages/
  - Home
  - History

- services/
  - API client (axios)
  - WebSocket client

---

## 📦 Database Design (Optional)

### Users
- id
- email
- password
- history

### Jobs
- jobId
- fileName
- status
- inputFormat
- outputFormat

---

## 🐳 Deployment Architecture

- Docker containers:
  - API server container
  - Worker container(s)
  - Redis container

- docker-compose setup:
  - orchestrates services
  - manages scaling

- Optional:
  - Nginx reverse proxy
  - Kubernetes cluster deployment

---

## 📈 Why This Project Fits a Concurrent Programming Course

- Demonstrates multithreading (worker_threads)
- Implements producer–consumer model
- Uses job scheduling system
- Handles synchronization via queue
- Supports parallel execution
- Includes real-world load balancing
- Implements fault tolerance (retry system)
- Models real cloud processing systems

---

## 🧪 Testing Strategy

- Unit tests for conversion logic
- Load testing with bulk uploads
- Stress testing worker limits
- Failure simulation tests
- Queue overflow testing

---

## 🔥 Future Improvements

- Distributed worker clusters
- GPU acceleration for video processing
- AI-based file optimization
- Priority-based scheduling
- WebAssembly optimization
- Auto-scaling worker pool

---

## 🏁 Summary

- This is a scalable file processing system
- Uses real multithreading and concurrency patterns
- Implements production-level architecture
- Works as a mini cloud processing platform
- Fully satisfies concurrent programming requirements