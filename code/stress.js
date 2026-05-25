const fs = require('fs');
const path = require('path');
const axios = require('axios');
const FormData = require('form-data');

const API = 'http://localhost:3001/api';
const FILES = ['test1.txt', 'test2.txt', 'test3.txt', 'test4.txt', 'test5.txt'];
const TARGET = 'md';

async function uploadAll() {
  console.log('📤 Uploading all files at once...\n');
  const form = new FormData();
  FILES.forEach(f => form.append('files', fs.createReadStream(f), path.basename(f)));
  form.append('targetFormat', TARGET);

  const { data } = await axios.post(`${API}/upload`, form, { headers: form.getHeaders() });
  const jobIds = data.jobs.map(j => j.jobId);
  console.log(`✅ ${jobIds.length} jobs queued\n`);
  return jobIds;
}

async function pollUntilDone(jobIds) {
  const start = Date.now();
  const done = new Set();
  const failed = new Set();

  console.log('⏳ Polling job status...\n');

  while (done.size + failed.size < jobIds.length) {
    const { data } = await axios.get(`${API}/jobs`);
    const stats = await axios.get(`${API}/stats`);

    console.clear();
    console.log('━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━');
    console.log(`  THREADCONV STRESS TEST`);
    console.log(`  Active workers: ${stats.data.activeWorkers}/${stats.data.maxWorkers}  |  Queue: ${stats.data.queueSize}`);
    console.log(`  Elapsed: ${((Date.now() - start) / 1000).toFixed(1)}s`);
    console.log('━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n');

    for (const job of data.jobs) {
      if (!jobIds.includes(job.jobId)) continue;

      const bar = progressBar(job.progress || 0);
      const status = job.status.toUpperCase().padEnd(10);
      console.log(`  ${job.originalName.padEnd(12)} [${bar}] ${job.progress || 0}%  ${status} ${job.progressMessage || ''}`);

      if (job.status === 'completed') done.add(job.jobId);
      if (job.status === 'failed') failed.add(job.jobId);
    }

    console.log(`\n  ✅ Done: ${done.size}  ❌ Failed: ${failed.size}  ⏳ Pending: ${jobIds.length - done.size - failed.size}`);

    if (done.size + failed.size < jobIds.length) {
      await sleep(500);
    }
  }

  const total = ((Date.now() - start) / 1000).toFixed(2);
  console.log(`\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━`);
  console.log(`  ALL DONE in ${total}s`);
  console.log(`  ${done.size} converted  |  ${failed.size} failed`);
  console.log(`━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n`);
}

function progressBar(pct) {
  const filled = Math.round(pct / 5);
  return '█'.repeat(filled) + '░'.repeat(20 - filled);
}

function sleep(ms) {
  return new Promise(r => setTimeout(r, ms));
}

(async () => {
  try {
    const jobIds = await uploadAll();
    await pollUntilDone(jobIds);
  } catch (e) {
    console.error('Error:', e.message);
  }
})();