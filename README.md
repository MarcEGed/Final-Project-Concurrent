# ⚙️ Multithreaded Web File Converter

A scalable web-based file conversion system using multithreading, job queues, and real-time processing.

This project demonstrates:
- concurrency
- multithreading
- producer–consumer architecture
- scalable backend design

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