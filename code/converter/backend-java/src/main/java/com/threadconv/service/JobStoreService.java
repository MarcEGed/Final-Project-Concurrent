package com.threadconv.service;

import com.threadconv.model.Job;
import com.threadconv.model.JobStatus;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe store for all jobs ever created in this session.
 *
 * ConcurrentHashMap provides:
 *  - lock-striping (not one global lock) so reads and writes to different
 *    keys never contend with each other (Week 3 — deadlock avoidance /
 *    fine-grained locking)
 *  - guaranteed visibility of put() to subsequent get() calls on any thread
 *    (Week 4 — happens-before)
 *
 * Content-hash deduplication:
 *  - contentIndex maps SHA-256(fileBytes):targetFormat → jobId
 *  - findActiveByContentKey() returns an existing job that is still
 *    processing or whose output file is still on disk, allowing the API
 *    to skip re-conversion of an identical file upload
 */
@Service
public class JobStoreService {

    private final ConcurrentHashMap<String, Job>    jobs         = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> contentIndex = new ConcurrentHashMap<>();

    public void put(Job job) {
        jobs.put(job.getJobId(), job);
        if (job.getContentKey() != null) {
            // putIfAbsent: first registration wins; concurrent duplicates fall through to Worker-level guard
            contentIndex.putIfAbsent(job.getContentKey(), job.getJobId());
        }
    }

    public Job get(String jobId) {
        return jobs.get(jobId);
    }

    public Collection<Job> getAll() {
        return jobs.values();
    }

    /**
     * Returns an existing job for the given content key if it is still worth
     * reusing: QUEUED, PROCESSING, or COMPLETED with the output file present.
     * Returns null for FAILED jobs or COMPLETED jobs whose file has been deleted.
     */
    public Job findActiveByContentKey(String contentKey) {
        String jobId = contentIndex.get(contentKey);
        if (jobId == null) return null;

        Job job = jobs.get(jobId);
        if (job == null) return null;

        JobStatus s = job.getStatus();
        if (s == JobStatus.QUEUED || s == JobStatus.PROCESSING) return job;
        if (s == JobStatus.COMPLETED && Files.exists(Path.of(job.getOutputPath()))) return job;
        return null;
    }

    /** Removes completed and failed jobs from the in-memory store and content index. */
    public void clearCompleted() {
        jobs.values().removeIf(j -> {
            if (j.getStatus() == JobStatus.COMPLETED || j.getStatus() == JobStatus.FAILED) {
                if (j.getContentKey() != null) contentIndex.remove(j.getContentKey());
                return true;
            }
            return false;
        });
    }
}
