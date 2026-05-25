/**
 * workerPool.js — Manages a fixed pool of worker_threads
 * Pulls jobs from jobQueue, spawns workers, emits real-time updates via socketService
 */

const { Worker } = require('worker_threads');
const path = require('path');
const os = require('os');
const jobQueue = require('./jobQueue');

const MAX_WORKERS = Math.max(2, os.cpus().length - 1);
let activeWorkers = 0;
let socketService = null;

function setSocketService(svc) {
  socketService = svc;
}

function emit(event, data) {
  if (socketService) socketService.emit(event, data);
}

function processNext() {
  if (activeWorkers >= MAX_WORKERS) return;
  if (!jobQueue.hasNext()) return;

  const job = jobQueue.dequeue();
  if (!job) return;

  activeWorkers++;
  jobQueue.updateJob(job.jobId, { status: 'processing', startedAt: Date.now() });
  emit('job:processing', { jobId: job.jobId, status: 'processing' });

  const worker = new Worker(path.join(__dirname, 'worker.js'), { workerData: job });

  worker.on('message', (msg) => {
    if (msg.type === 'progress') {
      jobQueue.updateJob(job.jobId, { progress: msg.progress, progressMessage: msg.message });
      emit('job:progress', { jobId: job.jobId, progress: msg.progress, message: msg.message });
    } else if (msg.type === 'done') {
      jobQueue.updateJob(job.jobId, {
        status: 'completed',
        progress: 100,
        outputPath: msg.outputPath,
        completedAt: Date.now()
      });
      emit('job:done', { jobId: job.jobId, status: 'completed' });
    } else if (msg.type === 'error') {
      const requeued = jobQueue.requeueFailed(job);
      if (!requeued) {
        jobQueue.updateJob(job.jobId, { status: 'failed', error: msg.error, failedAt: Date.now() });
        emit('job:failed', { jobId: job.jobId, status: 'failed', error: msg.error });
      } else {
        emit('job:requeued', { jobId: job.jobId, attempt: job.attempts });
      }
    }
  });

  worker.on('error', (err) => {
    jobQueue.updateJob(job.jobId, { status: 'failed', error: err.message });
    emit('job:failed', { jobId: job.jobId, error: err.message });
  });

  worker.on('exit', () => {
    activeWorkers--;
    processNext(); // pick up next job when slot frees
  });
}

// Watch the queue for new jobs
jobQueue.on('job:enqueued', () => processNext());
jobQueue.on('job:requeued', () => processNext());

function getStats() {
  return { activeWorkers, maxWorkers: MAX_WORKERS, queueSize: jobQueue.size() };
}

module.exports = { setSocketService, processNext, getStats };
