/**
 * jobQueue.js — In-memory FIFO job queue with retry support
 * Producer–Consumer pattern: API pushes jobs, workerPool consumes them
 */

const { EventEmitter } = require('events');

class JobQueue extends EventEmitter {
  constructor() {
    super();
    this.queue = [];        // pending jobs
    this.jobs = new Map();  // all jobs by jobId (for status lookup)
  }

  enqueue(job) {
    job.status = 'queued';
    job.attempts = 0;
    job.createdAt = Date.now();
    this.jobs.set(job.jobId, job);
    this.queue.push(job);
    this.emit('job:enqueued', job);
    return job;
  }

  dequeue() {
    return this.queue.shift() || null;
  }

  hasNext() {
    return this.queue.length > 0;
  }

  size() {
    return this.queue.length;
  }

  getJob(jobId) {
    return this.jobs.get(jobId) || null;
  }

  updateJob(jobId, patch) {
    const job = this.jobs.get(jobId);
    if (job) Object.assign(job, patch);
    return job;
  }

  requeueFailed(job) {
    if (job.attempts < 3) {
      job.attempts++;
      job.status = 'queued';
      this.queue.push(job);
      this.emit('job:requeued', job);
      return true;
    }
    return false;
  }

  getAllJobs() {
    return Array.from(this.jobs.values());
  }

  clearCompleted() {
    for (const [id, job] of this.jobs.entries()) {
      if (job.status === 'completed' || job.status === 'failed') {
        this.jobs.delete(id);
      }
    }
  }
}

module.exports = new JobQueue();
