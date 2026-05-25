# 🚀 Stress Testing Guide - Push the System to 100%

## Quick Start: Run the Advanced Stress Test

```bash
# In the code/ folder
node stress-advanced.js
```

This will:
- ✅ Generate 100 large text files (500KB each)
- ✅ Upload all files at once (20 per batch)
- ✅ Show real-time monitoring of workers, queue, and progress
- ✅ Display average processing speed and success rate

---

## Stress Test Scenarios

### **Scenario 1: Light Load (Default)**
```javascript
numFiles: 100,
fileSize: 'large',      // 500KB each
fileTypes: ['text', 'text', 'text', 'image'],
uploadWaves: 1,
batchSize: 20,
```
**Result:** Queue builds up, workers stay busy, ~5-10 seconds total

---

### **Scenario 2: Heavy Load (PUSH THE LIMITS)**
Edit stress-advanced.js:
```javascript
STRESS_CONFIG = {
  numFiles: 500,         // ← 500 files instead of 100
  fileSize: 'large',
  fileTypes: ['image', 'image', 'image', 'image'],  // ← All CPU-intensive images
  uploadWaves: 1,
  batchSize: 50,         // ← Bigger batches
};
```
**Result:** 
- Workers will ALL be maxed out immediately
- Queue will fill with hundreds of pending jobs
- Backend will be under max stress
- Total time: 20-60 seconds depending on CPU

---

### **Scenario 3: Extreme Load (ABSOLUTE MAX)**
```javascript
STRESS_CONFIG = {
  numFiles: 1000,        // ← 1000 files!
  fileSize: 'large',
  fileTypes: ['text', 'text', 'text', 'text'],  // All text (faster batch upload)
  uploadWaves: 1,
  batchSize: 100,        // Upload 100 at a time
};
```
**Result:**
- Upload takes longer (bigger request size)
- Massive queue backlog (hundreds waiting)
- Watch workers stay at 100% until queue drains
- Total time: 60-120 seconds

---

### **Scenario 4: Wave Upload (Sustained Load)**
```javascript
STRESS_CONFIG = {
  numFiles: 200,
  fileSize: 'large',
  fileTypes: ['text', 'text', 'image', 'image'],
  uploadWaves: 5,        // ← Upload in 5 waves
  batchSize: 10,         // 10 files per batch
};
```
**Result:**
- First wave uploaded, workers start
- Queue never gets huge (steady trickle)
- Workers stay busy throughout
- See how queue gets managed smoothly

---

## How to Customize & Run Different Scenarios

### **Edit the config:**
```bash
# Open stress-advanced.js in your editor
# Change STRESS_CONFIG at the top
# Save
```

### **Key Parameters Explained:**

| Parameter | What It Does | Example |
|-----------|------------|---------|
| `numFiles` | How many files to generate | `100`, `500`, `1000` |
| `fileSize` | Size of each file | `'small'` (1KB), `'medium'` (100KB), `'large'` (500KB) |
| `fileTypes` | Mix of file types | `['text', 'text', 'image']` = 66% text, 33% image |
| `targetFormat` | Conversion target | `'md'`, `'json'`, `'html'` (text) |
| `uploadWaves` | Split uploads into waves | `1` (all at once), `5` (phased) |
| `batchSize` | Files per upload request | `20`, `50`, `100` |

---

## What to Look For During Testing

### **Real-Time Dashboard Shows:**

```
Active Workers: 7/7     (all CPU cores in use!)
Queue Size: 156         (156 waiting jobs)
Elapsed: 45.3s

Average Progress: [███████░░░░░░░░░░░░] 35%

✅ Completed: 89  |  🔄 Processing: 7  |  ⏳ Queued: 104  |  ❌ Failed: 0

Total Progress: 89/200 jobs done
```

**What this means:**
- **7/7 workers**: System is at max capacity
- **Queue 156**: 156 jobs waiting for a free worker slot
- **Average Progress 35%**: Processing is progressing normally
- **Processing 7**: Exactly matches max workers (good!)

---

## Performance Benchmarks

### **Your System Should Handle:**

| Load | Time | Queue Peak | CPU |
|------|------|-----------|-----|
| 100 files (500KB) | 10-15s | ~20-30 | 90-100% |
| 300 files (500KB) | 30-45s | ~100-150 | 100% (capped) |
| 500 files (500KB) | 60-90s | ~300+ | 100% (capped) |
| 1000 files (small) | 120-180s | ~500+ | 100% (capped) |

---

## Run Tests in Sequence (FULL STRESS TEST)

```bash
# Terminal 1: Backend
cd converter/backend
npm install
npm start

# Terminal 2: Frontend (optional, not needed for stress test)
cd converter/frontend
npm install
npm start

# Terminal 3: Stress Tests
cd code

# Test 1: Light load
node stress.js                  # Original (5 files)

# Test 2: Heavy load
node stress-advanced.js         # 100 files

# Then manually edit stress-advanced.js and run again with different configs

# Test 3: Extreme
# (edit config to 1000 files and run)
node stress-advanced.js
```

---

## Expected Behavior

### **Phase 1: Upload (1-5 seconds)**
```
📤 Uploading 100 files in batches...
  Uploading batch 1 (20 files)...
  Uploading batch 2 (20 files)...
  ...
✅ Uploaded 100 jobs
```

### **Phase 2: Processing (Bulk of time)**
```
⏳ Monitoring jobs...
Active Workers: 7/7  |  Queue Size: 87  |  Elapsed: 12.3s
Average Progress: [████████░░░░░░░░░░] 40%
✅ Completed: 34  |  🔄 Processing: 7  |  ⏳ Queued: 59  |  ❌ Failed: 0
```

### **Phase 3: Tail-off (Queue drains)**
```
Active Workers: 3/7  |  Queue Size: 0  |  Elapsed: 45.8s
Average Progress: [████████████████░░] 90%
✅ Completed: 93  |  🔄 Processing: 3  |  ⏳ Queued: 0  |  ❌ Failed: 0
```

### **Phase 4: Done**
```
╔════════════════════════════════════════════════════════╗
║               ✅ TEST COMPLETED ✅                    ║
╠════════════════════════════════════════════════════════╣
║  Total Time: 52.34s                                    ║
║  Successful: 100                                       ║
║  Failed: 0                                             ║
║  Success Rate: 100.00%                                 ║
╚════════════════════════════════════════════════════════╝
```

---

## Monitoring Backend Health

While stress test runs, open another terminal:

```bash
# Check backend logs (Terminal 1 where npm start is running)
# Should see messages like:
# [socket] client connected
# [cron] cleaned up old files
# Job events flowing through
```

---

## Tips to Push Even Harder

1. **Increase CPU Load**: Use 1000+ files
2. **Larger Files**: Change `fileSize: 'large'` to generate 5MB files
3. **More Diverse Types**: Mix image + text conversions
4. **Longer Waves**: Simulate sustained load with `uploadWaves: 10`
5. **Multiple Clients**: Run stress test in 2-3 terminals simultaneously!

```bash
# Terminal A
node stress-advanced.js

# Terminal B (while A is running)
node stress-advanced.js

# Terminal C (while A & B are running)
node stress-advanced.js
```
This will absolutely max out the system! 🔥

---

## Troubleshooting

**Q: Getting "Backend not running" error?**
- Make sure backend is started: `npm start` in `backend/` folder first

**Q: Seeing failures?**
- Likely out of memory or disk space
- Check `/uploads` and `/outputs` directories in backend folder
- Cron cleanup runs every 15 minutes

**Q: Want to see what's in the queue?**
```bash
# In another terminal, run in code/ folder:
curl http://localhost:3001/api/jobs
curl http://localhost:3001/api/stats
```

---

## Next Steps

Once you've pushed the system to limits, you can:
- ✅ Verify concurrency works (all workers busy)
- ✅ Check if bottlenecks exist (queue building)
- ✅ Monitor system resources (CPU/Memory)
- ✅ Calculate throughput (jobs/second)
- ✅ Identify where to optimize
