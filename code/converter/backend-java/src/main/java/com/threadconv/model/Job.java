package com.threadconv.model;

/**
 * Represents one conversion job. Immutable identity fields are final; mutable
 * status fields are volatile so worker-thread writes are immediately visible to
 * the HTTP-request threads that read them (Week 4 — visibility / happens-before).
 */
public class Job {

    // ── Identity (set once at creation, never mutated) ────────────────────────
    private final String jobId;
    private final String originalName;
    private final String inputPath;
    private final String outputPath;
    private final String outputFilename;
    private final String targetFormat;
    private final long   fileSize;
    private final long   createdAt;
    private final String contentKey;  // SHA-256(bytes) + ":" + targetFormat — used for deduplication

    // ── Mutable state (written by worker thread, read by HTTP threads) ────────
    private volatile JobStatus status          = JobStatus.QUEUED;
    private volatile int       progress        = 0;
    private volatile String    progressMessage = "";
    private volatile String    error           = null;
    private volatile long      completedAt     = 0;
    private volatile int       attempts        = 0;

    public Job(String jobId, String originalName, String inputPath,
               String outputPath, String outputFilename,
               String targetFormat, long fileSize, String contentKey) {
        this.jobId          = jobId;
        this.originalName   = originalName;
        this.inputPath      = inputPath;
        this.outputPath     = outputPath;
        this.outputFilename = outputFilename;
        this.targetFormat   = targetFormat;
        this.fileSize       = fileSize;
        this.createdAt      = System.currentTimeMillis();
        this.contentKey     = contentKey;
    }

    // ── Getters ───────────────────────────────────────────────────────────────
    public String    getJobId()          { return jobId; }
    public String    getOriginalName()   { return originalName; }
    public String    getInputPath()      { return inputPath; }
    public String    getOutputPath()     { return outputPath; }
    public String    getOutputFilename() { return outputFilename; }
    public String    getTargetFormat()   { return targetFormat; }
    public long      getFileSize()       { return fileSize; }
    public long      getCreatedAt()      { return createdAt; }
    public String    getContentKey()     { return contentKey; }
    public JobStatus getStatus()         { return status; }
    public int       getProgress()       { return progress; }
    public String    getProgressMessage(){ return progressMessage; }
    public String    getError()          { return error; }
    public long      getCompletedAt()    { return completedAt; }
    public int       getAttempts()       { return attempts; }

    // ── Setters (called only from worker thread or WorkerPoolService) ─────────
    public void setStatus(JobStatus s)      { this.status          = s; }
    public void setProgress(int p)          { this.progress        = p; }
    public void setProgressMessage(String m){ this.progressMessage = m; }
    public void setError(String e)          { this.error           = e; }
    public void setCompletedAt(long ts)     { this.completedAt     = ts; }
    public void incrementAttempts()         { this.attempts++; }
}
