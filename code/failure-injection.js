/**
 * failure-injection.js — Two injected failure scenarios for THREADCONV.
 *
 * Scenario A — Worker process kill (network-boundary failure + recovery)
 *   1. Confirm the Worker (port 3003) is healthy.
 *   2. Kill the Worker process (taskkill).
 *   3. Upload a file: the API's dispatch hits a dead network boundary, the
 *      WorkerClient times out / fails fast, and the job is marked FAILED with a
 *      clear fallback message (no crash, no hang) — this is the timeout+fallback path.
 *   4. Restart the Worker and wait for /worker/health.
 *   5. Upload again: the job now COMPLETES — this is the recovery path.
 *
 * Scenario B — Bounded-queue overload (backpressure / bounded resources)
 *   Fire a large concurrent burst of unique conversions at the API. The Worker's
 *   ThreadPoolExecutor has 7 threads + a LinkedBlockingQueue(200); once 207 jobs
 *   are in flight the AbortPolicy rejects further work with HTTP 503, the API
 *   marks those jobs FAILED ("queue is full") and increments totalRejected.
 *   Overload behaviour is intentional and measurable — nothing is unbounded.
 *
 * Usage:  node failure-injection.js            (runs both scenarios)
 *         node failure-injection.js A           (scenario A only)
 *         node failure-injection.js B           (scenario B only)
 *
 * Requires: API on :3001 and Worker on :3003 already running, JAVA_HOME set to
 * the JDK 21 install, and the worker JAR built (run-worker.bat builds it).
 */

const { execSync, spawn } = require('child_process');
const axios = require('axios');
const fs = require('fs');
const path = require('path');

const API           = 'http://localhost:3001/api';
const WORKER_HEALTH = 'http://localhost:3003/worker/health';
const WORKER_DIR    = path.resolve(__dirname, 'converter/backend-worker');
const WORKER_JAR    = path.join(WORKER_DIR, 'build/libs/threadconv-worker.jar');
const JAVA          = process.env.JAVA_HOME
  ? path.join(process.env.JAVA_HOME, 'bin', 'java.exe')
  : 'java';

const TMP = path.join(__dirname, '.fi-tmp');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ── Output capture (tee to console + evidence file) ─────────────────────────
const lines = [];
function log(s = '') { console.log(s); lines.push(s); }

// ── Helpers ─────────────────────────────────────────────────────────────────

function ensureBaseVideo() {
  if (!fs.existsSync(TMP)) fs.mkdirSync(TMP, { recursive: true });
  const base = path.join(TMP, 'base.mp4');
  if (!fs.existsSync(base)) {
    execSync(`ffmpeg -f lavfi -i testsrc=s=32x32:d=1 -pix_fmt yuv420p -y "${base}"`, { stdio: 'pipe' });
  }
  return base;
}

// Make a byte-unique tiny video fast (stream-copy + unique metadata, no re-encode).
function uniqueVideo(base, i) {
  const out = path.join(TMP, `fi-${i}.mp4`);
  execSync(`ffmpeg -i "${base}" -c copy -metadata comment="fi-${i}-${Date.now()}" -y "${out}"`, { stdio: 'pipe' });
  return out;
}

async function uploadOne(filePath, targetFormat) {
  const FormData = require('form-data');
  const form = new FormData();
  form.append('files', fs.createReadStream(filePath), path.basename(filePath));
  form.append('targetFormat', targetFormat);
  try {
    const { data } = await axios.post(`${API}/upload`, form, {
      headers: form.getHeaders(), maxContentLength: Infinity, maxBodyLength: Infinity,
    });
    return { ok: true, job: data.jobs[0] };
  } catch (e) {
    return { ok: false, status: e.response?.status, error: e.message };
  }
}

async function getJob(jobId) {
  try { return (await axios.get(`${API}/jobs/${jobId}`)).data; } catch { return null; }
}

async function getStats() {
  try { return (await axios.get(`${API}/stats`)).data; } catch { return null; }
}

function workerPid() {
  try {
    const out = execSync('netstat -ano', { encoding: 'utf8' });
    for (const ln of out.split('\n')) {
      if (ln.includes(':3003') && ln.includes('LISTENING')) {
        const parts = ln.trim().split(/\s+/);
        return parts[parts.length - 1];
      }
    }
  } catch {}
  return null;
}

async function workerHealthy() {
  try { return (await axios.get(WORKER_HEALTH, { timeout: 1500 })).data?.status === 'ok'; }
  catch { return false; }
}

function killWorker() {
  const pid = workerPid();
  if (!pid) { log('  (no worker process found on :3003)'); return null; }
  execSync(`taskkill /F /PID ${pid}`, { stdio: 'pipe' });
  return pid;
}

function startWorker() {
  const child = spawn(JAVA, ['-jar', WORKER_JAR], {
    cwd: WORKER_DIR, detached: true, stdio: 'ignore',
  });
  child.unref();
}

async function waitForWorker(timeoutMs = 40000) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    if (await workerHealthy()) return true;
    await sleep(1000);
  }
  return false;
}

async function pollUntilTerminal(jobId, timeoutMs = 15000) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    const j = await getJob(jobId);
    if (j && (j.status === 'completed' || j.status === 'failed')) return j;
    await sleep(400);
  }
  return await getJob(jobId);
}

// ── Scenario A ──────────────────────────────────────────────────────────────
async function scenarioA() {
  log('\n══════════════════════════════════════════════════════════════');
  log(' SCENARIO A — Worker process kill → fallback → recovery');
  log('══════════════════════════════════════════════════════════════');

  const base = ensureBaseVideo();

  log('\n[1] Worker health before kill: ' + (await workerHealthy() ? 'OK ✅' : 'DOWN ❌'));

  log('\n[2] Killing the Worker process...');
  const pid = killWorker();
  log('    taskkill PID ' + pid + ' → worker is now DOWN');
  await sleep(1500);
  log('    Worker health after kill: ' + (await workerHealthy() ? 'OK' : 'DOWN ✅ (as expected)'));

  log('\n[3] Uploading a job while the Worker is DOWN (expect fallback → FAILED)...');
  const f1 = uniqueVideo(base, 'A-down');
  const r1 = await uploadOne(f1, 'mkv');
  log('    Upload HTTP response job: ' + JSON.stringify(r1.job || r1));
  const j1 = r1.ok ? await getJob(r1.job.jobId) : null;
  if (j1) log('    Job state: status=' + j1.status + '  error="' + (j1.error || '') + '"');
  const sA = await getStats();
  log('    Stats: totalRejected=' + sA?.totalRejected + '  (fallback counted as rejected)');

  log('\n[4] Restarting the Worker...');
  startWorker();
  const up = await waitForWorker();
  log('    Worker health after restart: ' + (up ? 'OK ✅ (recovered)' : 'STILL DOWN ❌'));

  log('\n[5] Uploading another job after recovery (expect COMPLETED)...');
  const f2 = uniqueVideo(base, 'A-up');
  const r2 = await uploadOne(f2, 'mkv');
  const j2 = r2.ok ? await pollUntilTerminal(r2.job.jobId) : null;
  log('    Job state: status=' + (j2?.status) + '  (' + j2?.jobId + ')');
  log('\n  RESULT: ' + (j1?.status === 'failed' && j2?.status === 'completed'
    ? '✅ failed-while-down then recovered-after-restart — timeout/fallback + recovery proven'
    : '⚠️  unexpected — check services'));
}

// ── Scenario B ──────────────────────────────────────────────────────────────
async function scenarioB(burst = 240) {
  log('\n══════════════════════════════════════════════════════════════');
  log(' SCENARIO B — Bounded-queue overload → 503 backpressure');
  log('══════════════════════════════════════════════════════════════');
  log(' Worker capacity = 7 threads + queue(200) = 207 in flight; beyond that → 503');

  if (!(await workerHealthy())) {
    log('\n  Worker is down — starting it first...');
    startWorker();
    await waitForWorker();
  }

  log('\n[1] Generating ' + burst + ' unique tiny videos...');
  const base = ensureBaseVideo();
  const files = [];
  for (let i = 0; i < burst; i++) files.push(uniqueVideo(base, 'B-' + i));

  const before = await getStats();
  log('[2] Firing all ' + burst + ' uploads concurrently (single burst)...');
  const results = await Promise.all(files.map((f) => uploadOne(f, 'mkv')));

  let accepted = 0, rejected = 0;
  for (const r of results) {
    const st = r.ok ? r.job.status : 'http_' + r.status;
    if (st === 'failed' || st === 'http_503') rejected++; else accepted++;
  }
  await sleep(1500);
  const after = await getStats();

  log('\n  RESULT:');
  log('    burst submitted        : ' + burst);
  log('    accepted (queued/done) : ' + accepted);
  log('    rejected at upload     : ' + rejected);
  log('    totalRejected (stats)  : ' + before?.totalRejected + ' → ' + after?.totalRejected
    + '  (Δ ' + ((after?.totalRejected ?? 0) - (before?.totalRejected ?? 0)) + ')');
  log('    ' + (rejected > 0 || (after?.totalRejected > before?.totalRejected)
    ? '✅ queue overflowed and rejected excess with 503 — bounded resources proven'
    : '⚠️  no rejections — increase burst (queue drained faster than it filled)'));
}

// ── Cleanup ──────────────────────────────────────────────────────────────────
function cleanup() {
  try { if (fs.existsSync(TMP)) fs.rmSync(TMP, { recursive: true, force: true }); } catch {}
}

// ── Main ──────────────────────────────────────────────────────────────────────
(async () => {
  const which = (process.argv[2] || 'AB').toUpperCase();
  log('THREADCONV — Failure Injection   (' + new Date().toISOString() + ')');
  try {
    if (which.includes('A')) await scenarioA();
    if (which.includes('B')) await scenarioB();
  } catch (e) {
    log('\n❌ Injection error: ' + e.message);
  } finally {
    cleanup();
    const out = path.join(__dirname, '..', 'evidence', 'failure-injection-output.txt');
    try {
      fs.mkdirSync(path.dirname(out), { recursive: true });
      fs.writeFileSync(out, lines.join('\n') + '\n');
      log('\n📝 Evidence written to evidence/failure-injection-output.txt');
    } catch (e) { log('Could not write evidence file: ' + e.message); }
  }
})();
