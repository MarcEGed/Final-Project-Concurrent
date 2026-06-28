/**
 * stress-advanced.js — Comprehensive stress test with dynamic file generation
 * Generates hundreds of files to push the system to max capacity
 */

const fs = require('fs');
const path = require('path');
const axios = require('axios');
const FormData = require('form-data');
const { execSync } = require('child_process');

const API = 'http://localhost:3001/api';

// ── Configuration ─────────────────────────────────────────────────────────
const STRESS_CONFIG = {
  numFiles: 50,            // How many files to generate (20 videos = ~4-5 min total)
  fileSize: 'large',       // (doesn't matter for videos — duration is fixed at 3s)
  fileTypes: ['video'],    // 100% VIDEO FILES — generates real MP4s via FFmpeg
  targetFormat: 'mkv',    // Conversion target: mp4, mkv, webm, avi, mov
  uploadWaves: 5,          // How many waves (1 = all at once)
  batchSize: 20,           // Files per upload request
};

// ── File Size Mapping ─────────────────────────────────────────────────────
const SIZES = {
  small: 1024,             // 1 KB
  medium: 100 * 1024,      // 100 KB
  large: 500 * 1024,       // 500 KB
};

// ── Generate Test Files ───────────────────────────────────────────────────

async function generateTestFiles() {
  console.log(`🔨 Generating ${STRESS_CONFIG.numFiles} test files (${STRESS_CONFIG.fileSize})...\n`);
  const testDir = path.join(__dirname, 'stress-files');
  if (!fs.existsSync(testDir)) fs.mkdirSync(testDir, { recursive: true });

  const files = [];
  const sizeBytes = SIZES[STRESS_CONFIG.fileSize];

  try {
    for (let i = 0; i < STRESS_CONFIG.numFiles; i++) {
      const fileType = STRESS_CONFIG.fileTypes[i % STRESS_CONFIG.fileTypes.length];
      let filename, filepath, ext;

      if (fileType === 'text') {
        ext = 'txt';
        filename = `stress-file-${i + 1}-${fileType}.${ext}`;
        filepath = path.join(testDir, filename);
        
        // Generate large text file
        const content = `File ${i + 1}\n`.repeat(Math.floor(sizeBytes / 50)) + 
                       'Lorem ipsum dolor sit amet, consectetur adipiscing elit. '.repeat(Math.floor(sizeBytes / 100));
        fs.writeFileSync(filepath, content);
      } else if (fileType === 'image') {
        ext = 'png';
        filename = `stress-file-${i + 1}-${fileType}.${ext}`;
        filepath = path.join(testDir, filename);
        
        // Generate simple PNG (1x1 pixel) as placeholder
        const pngData = Buffer.from([
          0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D,
          0x49, 0x48, 0x44, 0x52, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
          0x08, 0x02, 0x00, 0x00, 0x00, 0x90, 0x77, 0x53, 0xDE, 0x00, 0x00, 0x00,
          0x0C, 0x49, 0x44, 0x41, 0x54, 0x08, 0x99, 0x63, 0xF8, 0x0F, 0x00, 0x00,
          0x01, 0x01, 0x01, 0x00, 0x1A, 0x16, 0xEE, 0x3B, 0x47, 0x00, 0x00, 0x00,
          0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE, 0x42, 0x60, 0x82
        ]);
        fs.writeFileSync(filepath, pngData);
      } else if (fileType === 'video') {
        ext = 'mp4';
        filename = `stress-file-${i + 1}-${fileType}.${ext}`;
        filepath = path.join(testDir, filename);
        
        // Generate a real 3-second test video using FFmpeg (testsrc pattern + sine audio).
        // IMPORTANT: each file must be byte-unique, otherwise the API's content-hash
        // deduplication (sha256(bytes)+targetFormat) collapses all N uploads into a
        // single job and the test never actually exercises concurrency. We vary the
        // audio frequency per file and stamp unique metadata so every output differs.
        try {
          const uniqueFreq = 400 + i * 7;            // distinct audio per file
          const uniqueTag  = `stress-${i}-${Date.now()}`;
          execSync(
            `ffmpeg -f lavfi -i testsrc=s=320x240:d=3 -f lavfi -i sine=f=${uniqueFreq}:d=3 ` +
            `-pix_fmt yuv420p -metadata comment="${uniqueTag}" -y "${filepath}"`,
            { stdio: 'pipe' }
          );
        } catch (e) {
          // FFmpeg not found or failed — stub files will cause backend conversion errors.
          // Install FFmpeg and add it to PATH to run a proper video stress test.
          console.warn(`\n⚠️  FFmpeg unavailable: stub MP4 written for file ${i + 1}. Backend conversions will fail.`);
          console.warn('   Install FFmpeg (https://ffmpeg.org/download.html) and add it to PATH.\n');
          const mp4Header = Buffer.from([
            0x00, 0x00, 0x00, 0x20, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6F, 0x6D,
            0x00, 0x00, 0x00, 0x00, 0x69, 0x73, 0x6F, 0x6D, 0x69, 0x73, 0x6F, 0x32,
            0x6D, 0x70, 0x34, 0x31, 0x00, 0x00, 0x00, 0x08, 0x77, 0x69, 0x64, 0x65
          ]);
          fs.writeFileSync(filepath, mp4Header);
        }
      }

      files.push(filepath);
      
      // Progress indicator
      if ((i + 1) % 10 === 0) {
        console.log(`  ✓ Generated ${i + 1}/${STRESS_CONFIG.numFiles} files`);
      }
    }

    console.log(`\n✅ All test files generated in stress-files/\n`);
    return files;
  } catch (e) {
    console.error('❌ File generation error:', e.message);
    throw e;
  }
}

// ── Upload in Batches ─────────────────────────────────────────────────────

async function uploadBatch(filePaths) {
  const form = new FormData();
  filePaths.forEach(f => form.append('files', fs.createReadStream(f), path.basename(f)));
  form.append('targetFormat', STRESS_CONFIG.targetFormat);

  try {
    const { data } = await axios.post(`${API}/upload`, form, { 
      headers: form.getHeaders(),
      maxContentLength: Infinity,
      maxBodyLength: Infinity,
    });
    return data.jobs.map(j => j.jobId);
  } catch (e) {
    console.error('Upload error:', e.message);
    return [];
  }
}

async function uploadAllFiles(files) {
  console.log(`📤 Uploading ${files.length} files in batches...\n`);
  const allJobIds = [];
  
  for (let batch = 0; batch < files.length; batch += STRESS_CONFIG.batchSize) {
    const batchFiles = files.slice(batch, batch + STRESS_CONFIG.batchSize);
    console.log(`  Uploading batch ${Math.floor(batch / STRESS_CONFIG.batchSize) + 1} (${batchFiles.length} files)...`);
    
    const jobIds = await uploadBatch(batchFiles);
    allJobIds.push(...jobIds);
    
    // Small delay between batches
    await sleep(200);
  }

  console.log(`\n✅ Uploaded ${allJobIds.length} jobs\n`);
  return allJobIds;
}

// ── Monitor Progress ──────────────────────────────────────────────────────

async function pollUntilDone(jobIds) {
  const start = Date.now();
  const done = new Set();
  const failed = new Set();
  const processing = new Set();

  console.log('⏳ Monitoring jobs... Press Ctrl+C to stop\n');

  while (done.size + failed.size < jobIds.length) {
    try {
      const { data: jobsData } = await axios.get(`${API}/jobs`);
      const { data: statsData } = await axios.get(`${API}/stats`);

      console.clear();
      console.log('╔═══════════════════════════════════════════════════════════╗');
      console.log('║        THREADCONV ADVANCED STRESS TEST                    ║');
      console.log('╠═══════════════════════════════════════════════════════════╣');
      console.log(`║  Active Workers: ${statsData.activeWorkers}/${statsData.maxWorkers}  |  Queue Size: ${statsData.queueSize}  |  Elapsed: ${((Date.now() - start) / 1000).toFixed(1)}s  ║`);
      console.log('╠═══════════════════════════════════════════════════════════╣');

      let totalProgress = 0;
      let processCount = 0;

      for (const job of jobsData.jobs) {
        if (!jobIds.includes(job.jobId)) continue;

        if (job.status === 'completed') done.add(job.jobId);
        if (job.status === 'failed') failed.add(job.jobId);
        if (job.status === 'processing') {
          processing.add(job.jobId);
          totalProgress += job.progress || 0;
          processCount++;
        }
      }

      const avgProgress = processCount > 0 ? Math.round(totalProgress / processCount) : 0;
      const bar = progressBar(avgProgress);
      
      console.log(`║  Average Progress: [${bar}] ${avgProgress}%                    ║`);
      console.log(`║                                                           ║`);
      console.log(`║  ✅ Completed: ${String(done.size).padEnd(3)} | 🔄 Processing: ${String(processCount).padEnd(3)} | ⏳ Queued: ${String(Math.max(0, jobIds.length - done.size - failed.size - processCount)).padEnd(3)} | ❌ Failed: ${String(failed.size).padEnd(3)} ║`);
      console.log(`║                                                           ║`);
      console.log(`║  Total Progress: ${done.size}/${jobIds.length} jobs done                      ║`);
      console.log('╚═══════════════════════════════════════════════════════════╝\n');

      if (done.size + failed.size >= jobIds.length) break;

      await sleep(1000);
    } catch (e) {
      console.error('\n❌ Poll error:', e.message);
      if (e.response) {
        console.error('Response status:', e.response.status);
        console.error('Response data:', e.response.data);
      }
      console.error('Full error:', e.toString());
      await sleep(1000);
    }
  }

  const total = ((Date.now() - start) / 1000).toFixed(2);
  console.log('\n╔═══════════════════════════════════════════════════════════╗');
  console.log('║                   ✅ TEST COMPLETED ✅                    ║');
  console.log('╠═══════════════════════════════════════════════════════════╣');
  console.log(`║  Total Time: ${String(total + 's').padEnd(47)} ║`);
  console.log(`║  Successful: ${String(done.size).padEnd(47)} ║`);
  console.log(`║  Failed: ${String(failed.size).padEnd(50)} ║`);
  console.log(`║  Success Rate: ${String(((done.size / jobIds.length) * 100).toFixed(2) + '%').padEnd(44)} ║`);
  console.log('╚═══════════════════════════════════════════════════════════╝\n');

  // Cleanup
  console.log('🧹 Cleaning up stress test files...');
  const testDir = path.join(__dirname, 'stress-files');
  if (fs.existsSync(testDir)) {
    fs.readdirSync(testDir).forEach(f => fs.unlinkSync(path.join(testDir, f)));
    fs.rmdirSync(testDir);
  }
}

// ── Utilities ─────────────────────────────────────────────────────────────

function progressBar(pct) {
  const filled = Math.round(pct / 5);
  return '█'.repeat(filled) + '░'.repeat(20 - filled);
}

function sleep(ms) {
  return new Promise(r => setTimeout(r, ms));
}

// ── Main ──────────────────────────────────────────────────────────────────

(async () => {
  console.log('\n╔═══════════════════════════════════════════════════════════╗');
  console.log('║       THREADCONV STRESS TEST - Configuration             ║');
  console.log('╠═══════════════════════════════════════════════════════════╣');
  console.log(`║  Files to Generate: ${String(STRESS_CONFIG.numFiles).padEnd(35)} ║`);
  console.log(`║  File Size: ${String(STRESS_CONFIG.fileSize).padEnd(40)} ║`);
  console.log(`║  Target Format: ${String(STRESS_CONFIG.targetFormat).padEnd(35)} ║`);
  console.log(`║  Batch Size: ${String(STRESS_CONFIG.batchSize).padEnd(39)} ║`);
  console.log('╚═══════════════════════════════════════════════════════════╝\n');

  try {
    // Check backend is running
    try {
      await axios.get(`${API}/stats`);
    } catch {
      console.error('❌ Backend not running! Start backend with: npm start (in backend folder)');
      process.exit(1);
    }

    const files = await generateTestFiles();
    const jobIds = await uploadAllFiles(files);
    await pollUntilDone(jobIds);
  } catch (e) {
    console.error('Fatal error:', e.message);
  }
})();
