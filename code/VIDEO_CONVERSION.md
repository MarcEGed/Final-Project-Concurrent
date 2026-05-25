# 🎬 Video Conversion Support

Video conversion has been added to your file converter backend! This guide explains how to use it.

## **What Was Added**

### **Backend Changes**

1. **worker.js** - Added `convertVideo()` function using FFmpeg
2. **server.js** - Added video MIME types to allowed uploads
3. **stress-advanced.js** - Added video file generation and testing

### **Supported Video Formats**

| Format | Extension | MIME Type | Notes |
|--------|-----------|-----------|-------|
| MP4 | `.mp4` | `video/mp4` | Most compatible, H.264 codec |
| Matroska | `.mkv` | `video/x-matroska` | Open format, better quality |
| WebM | `.webm` | `video/webm` | Web-optimized, VP8/VP9 codec |
| AVI | `.avi` | `video/x-msvideo` | Older format, widely supported |
| MOV | `.mov` | `video/quicktime` | Apple QuickTime format |
| FLV | `.flv` | `video/x-flv` | Adobe Flash Video |
| WMV | `.wmv` | `video/x-ms-wmv` | Windows Media Video |

---

## **How Video Conversion Works**

### **Flow Diagram**

```
Frontend Upload (MP4 video)
    ↓
server.js validates MIME type (video/mp4) ✓
    ↓
jobQueue.enqueue() creates job { inputPath, outputPath, targetFormat: 'mkv' }
    ↓
workerPool spawns worker thread
    ↓
worker.js calls convertVideo()
    ↓
FFmpeg command:
  ffmpeg -i input.mp4 -c:v libx264 -c:a aac -q:v 5 output.mkv
    ↓
Progress updates emitted every frame
    ↓
job:done event sent to frontend with output file location
```

### **Implementation Details**

The `convertVideo()` function in [worker.js](converter/backend/worker.js):

```javascript
async function convertVideo(inputPath, outputPath, targetFormat) {
  // 1. Create FFmpeg command based on target format
  // 2. Monitor progress and emit updates (10-90%)
  // 3. Return when complete or error
}
```

**Codec Selection:**
- **Video**: `libx264` (H.264 - most compatible)
- **Audio**: `aac` (AAC - broadly supported)
- **Quality**: `-q:v 5` (medium quality, good balance)

**Format-Specific Options:**
- `.webm` uses `libvpx` (VP8) and `libopus` (Opus audio)
- `.mkv` uses standard H.264 + AAC
- `.mp4` uses standard H.264 + AAC

---

## **Test Video Conversion**

### **1. Start Backend**

```bash
cd code\converter\backend
npm start
```

Should see:
```
🚀 File Converter API running on http://localhost:3001
   Worker threads: up to 7 parallel workers
```

### **2. Run Stress Test with Videos**

Edit [stress-advanced.js](stress-advanced.js) line 15-22:

```javascript
const STRESS_CONFIG = {
  numFiles: 50,                    // 50 test videos
  fileSize: 'large',               // (doesn't matter for videos)
  fileTypes: ['video', 'video', 'video'],  // 100% video files
  targetFormat: 'webm',            // Convert to WebM
  uploadWaves: 1,                  // All at once
  batchSize: 10,                   // 10 per batch
};
```

Then run:
```bash
cd code
node stress-advanced.js
```

**Expected Output:**

```
🔨 Generating 50 test files (large)...
  ✓ Generated 10/50 files
  ✓ Generated 20/50 files
  ...
✅ All test files generated in stress-files/

📤 Uploading 50 files in batches...
  Uploading batch 1 (10 files)...
  Uploading batch 2 (10 files)...
  ...
✅ Uploaded 50 jobs

⏳ Monitoring jobs...
Active Workers: 7/7  |  Queue Size: 43  |  Elapsed: 2.3s
Average Progress: [██████░░░░░░░░░░░░] 30%
✅ Completed: 7  |  🔄 Processing: 7  |  ⏳ Queue: 36  |  ❌ Failed: 0

... (continues until all complete)

╔════════════════════════════════════════════════════════╗
║               ✅ TEST COMPLETED ✅                    ║
║  Total Time: 45.23s                                   ║
║  Successful: 50                                        ║
║  Failed: 0                                             ║
║  Success Rate: 100.00%                                 ║
╚════════════════════════════════════════════════════════╝
```

---

## **Test Different Scenarios**

### **Scenario 1: Text + Video Mix**

```javascript
fileTypes: ['text', 'text', 'video'],  // 66% text, 33% video
targetFormat: 'mp4',                   // Convert videos to MP4
```

### **Scenario 2: Heavy Video Load**

```javascript
numFiles: 100,
fileTypes: ['video', 'video', 'video', 'video'],  // All videos
targetFormat: 'mkv',
```

### **Scenario 3: Format Conversion Chain**

```javascript
numFiles: 50,
fileTypes: ['video'],
targetFormat: 'webm',  // Convert to WebM (useful for web)
```

---

## **Video Processing Characteristics**

### **Processing Time**

Video conversion is **CPU-intensive** and **time-consuming**:

| Video | Duration | Conversion Time |
|-------|----------|-----------------|
| 3-second test video | 3s | 5-10 seconds |
| 30-second video | 30s | 30-60 seconds |
| 2-minute video | 2min | 2-5 minutes |

**This is why video is great for stress testing** - it keeps workers busy longer!

### **Memory Usage**

Video processing uses more memory than text/images:
- Each video worker: 50-200 MB
- Max 7 workers = up to 1.4 GB potential

### **Progress Tracking**

Video progress is estimated by timecode:
```
Time processed / Total video duration = % complete
```

So a 30-second video will show:
- 10% at 3 seconds processed
- 50% at 15 seconds processed  
- 90% at 27 seconds processed
- 100% when complete

---

## **Troubleshooting**

### **Error: "Video conversion failed: No such file or directory"**

FFmpeg is not installed. Install it:

**Windows:**
```bash
# Using Chocolatey (if installed)
choco install ffmpeg

# Or download from: https://ffmpeg.org/download.html
```

**macOS:**
```bash
brew install ffmpeg
```

**Linux:**
```bash
sudo apt-get install ffmpeg  # Ubuntu/Debian
sudo yum install ffmpeg      # CentOS/RHEL
```

Then restart the backend.

---

### **Error: "Unsupported video format"**

Check that your target format is in the supported list above. Valid formats:
- `mp4`, `mkv`, `avi`, `mov`, `webm`, `flv`, `wmv`

---

### **Video Takes Too Long**

Video conversion is inherently slow. If you want faster processing:

1. **Use smaller videos** (< 10 seconds)
2. **Lower quality settings** (edit worker.js line 75: change `-q:v 5` to `-q:v 8`)
3. **Test with text files first** (they're faster)

---

## **Customizing Video Codec**

To change video quality or codec, edit [worker.js](converter/backend/worker.js) around line 75:

```javascript
// Current (medium quality)
.outputOptions(['-c:v', 'libx264', '-c:a', 'aac', '-q:v', '5'])

// For higher quality (slower)
.outputOptions(['-c:v', 'libx264', '-c:a', 'aac', '-q:v', '0'])  // 0 = lossless

// For faster encoding (lower quality)
.outputOptions(['-c:v', 'libx264', '-c:a', 'aac', '-q:v', '8'])  // 8 = lower quality
```

---

## **Next Steps**

Now your system supports:
- ✅ **Text** → Text (instant)
- ✅ **Images** → Images (fast, 1-2s)
- ✅ **Videos** → Videos (slow, 10-60s+)

This gives you a **full spectrum of processing demands** to test concurrency! 🚀

Try running the stress test with all three types:
```javascript
fileTypes: ['text', 'image', 'video'],  // All types
targetFormat: 'mp4',                    // Videos convert to mp4
```

You'll see:
- Text jobs complete instantly
- Image jobs complete in 1-2 seconds  
- Video jobs take 10+ seconds each
- All processing in parallel! 🎬
