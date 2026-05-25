/**
 * server.js — Express + Socket.IO entry point
 */

const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const cors = require('cors');
const multer = require('multer');
const path = require('path');
const fs = require('fs');
const { v4: uuidv4 } = require('uuid');
const archiver = require('archiver');
const cron = require('node-cron');

const jobQueue = require('./jobQueue');
const workerPool = require('./workerPool');
const socketService = require('./socketService');

// ── Directories ──────────────────────────────────────────────────────────────
const UPLOAD_DIR = path.join(__dirname, 'uploads');
const OUTPUT_DIR = path.join(__dirname, 'outputs');
[UPLOAD_DIR, OUTPUT_DIR].forEach((d) => fs.mkdirSync(d, { recursive: true }));

// ── Express setup ─────────────────────────────────────────────────────────────
const app = express();
const server = http.createServer(app);
const io = new Server(server, { cors: { origin: '*' } });

app.use(cors());
app.use(express.json());

// Static output downloads
app.use('/outputs', express.static(OUTPUT_DIR));

// ── Socket.IO ─────────────────────────────────────────────────────────────────
socketService.init(io);
workerPool.setSocketService(socketService);

// ── Multer ────────────────────────────────────────────────────────────────────
const ALLOWED_MIME = new Set([
  'image/jpeg', 'image/png', 'image/webp', 'image/avif', 'image/gif', 'image/tiff',
  'text/plain', 'text/html', 'text/csv', 'text/markdown',
  'application/json', 'application/xml', 'text/xml',
  'video/mp4', 'video/x-matroska', 'video/x-msvideo', 'video/quicktime', 'video/webm',
  'video/x-flv', 'video/x-ms-wmv', 'video/mpeg',
]);
const MAX_FILE_SIZE = 500 * 1024 * 1024; // 500 MB (for larger videos)

const storage = multer.diskStorage({
  destination: UPLOAD_DIR,
  filename: (_req, file, cb) => cb(null, `${uuidv4()}-${file.originalname}`),
});

const upload = multer({
  storage,
  limits: { fileSize: MAX_FILE_SIZE },
  fileFilter: (_req, file, cb) => {
    if (ALLOWED_MIME.has(file.mimetype)) cb(null, true);
    else cb(new Error(`Unsupported file type: ${file.mimetype}`));
  },
});

// ── Routes ────────────────────────────────────────────────────────────────────

// POST /api/upload — upload + enqueue conversion jobs
app.post('/api/upload', upload.array('files', 50), (req, res) => {
  if (!req.files || req.files.length === 0) {
    return res.status(400).json({ error: 'No files uploaded' });
  }
  const { targetFormat } = req.body;
  if (!targetFormat) return res.status(400).json({ error: 'targetFormat is required' });

  const jobs = req.files.map((file) => {
    const jobId = uuidv4();
    const outputFilename = `${jobId}.${targetFormat}`;
    const outputPath = path.join(OUTPUT_DIR, outputFilename);

    const job = jobQueue.enqueue({
      jobId,
      originalName: file.originalname,
      inputPath: file.path,
      outputPath,
      outputFilename,
      targetFormat,
      fileSize: file.size,
    });

    return { jobId: job.jobId, originalName: file.originalname, status: job.status };
  });

  res.json({ jobs });
});

// GET /api/jobs — list all jobs
app.get('/api/jobs', (_req, res) => {
  const jobs = jobQueue.getAllJobs().map((j) => ({
    jobId: j.jobId,
    originalName: j.originalName,
    targetFormat: j.targetFormat,
    status: j.status,
    progress: j.progress || 0,
    progressMessage: j.progressMessage || '',
    error: j.error || null,
    outputFilename: j.outputFilename || null,
    fileSize: j.fileSize,
    createdAt: j.createdAt,
    completedAt: j.completedAt || null,
  }));
  res.json({ jobs });
});

// GET /api/jobs/:jobId — single job status
app.get('/api/jobs/:jobId', (req, res) => {
  const job = jobQueue.getJob(req.params.jobId);
  if (!job) return res.status(404).json({ error: 'Job not found' });
  res.json(job);
});

// GET /api/download/:jobId — download single converted file
app.get('/api/download/:jobId', (req, res) => {
  const job = jobQueue.getJob(req.params.jobId);
  if (!job || job.status !== 'completed') {
    return res.status(404).json({ error: 'File not ready' });
  }
  res.download(job.outputPath, `converted-${job.originalName.replace(/\.[^.]+$/, '')}.${job.targetFormat}`);
});

// GET /api/download-zip — batch download as ZIP
app.get('/api/download-zip', (req, res) => {
  const jobIds = String(req.query.ids || '').split(',').filter(Boolean);
  const readyJobs = jobIds
    .map((id) => jobQueue.getJob(id))
    .filter((j) => j && j.status === 'completed' && fs.existsSync(j.outputPath));

  if (readyJobs.length === 0) {
    return res.status(400).json({ error: 'No completed files found' });
  }

  res.setHeader('Content-Type', 'application/zip');
  res.setHeader('Content-Disposition', 'attachment; filename="converted-files.zip"');

  const archive = archiver('zip', { zlib: { level: 6 } });
  archive.pipe(res);
  readyJobs.forEach((job) => {
    const name = `converted-${job.originalName.replace(/\.[^.]+$/, '')}.${job.targetFormat}`;
    archive.file(job.outputPath, { name });
  });
  archive.finalize();
});

// GET /api/stats — worker pool stats
app.get('/api/stats', (_req, res) => {
  res.json(workerPool.getStats());
});

// DELETE /api/jobs/clear — clear completed/failed jobs
app.delete('/api/jobs/clear', (_req, res) => {
  jobQueue.clearCompleted();
  res.json({ ok: true });
});

// ── Cleanup cron — delete files older than 1 hour ────────────────────────────
cron.schedule('*/15 * * * *', () => {
  const cutoff = Date.now() - 60 * 60 * 1000;
  for (const dir of [UPLOAD_DIR, OUTPUT_DIR]) {
    fs.readdirSync(dir).forEach((f) => {
      const fp = path.join(dir, f);
      try {
        const { mtimeMs } = fs.statSync(fp);
        if (mtimeMs < cutoff) fs.unlinkSync(fp);
      } catch (_) {}
    });
  }
  jobQueue.clearCompleted();
  console.log('[cron] cleaned up old files');
});

// ── Start ─────────────────────────────────────────────────────────────────────
const PORT = process.env.PORT || 3001;
server.listen(PORT, () => {
  console.log(`\n🚀 File Converter API running on http://localhost:${PORT}`);
  console.log(`   Worker threads: up to ${require('os').cpus().length - 1} parallel workers`);
});
