package com.threadconv.service;

import com.threadconv.model.Job;
import com.threadconv.model.JobStatus;
import org.springframework.stereotype.Service;

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
 */
@Service
public class JobStoreService {

    private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();

    public void put(Job job) {
        jobs.put(job.getJobId(), job);
    }

    public Job get(String jobId) {
        return jobs.get(jobId);
    }

    public Collection<Job> getAll() {
        return jobs.values();
    }

    /** Removes completed and failed jobs from the in-memory store. */
    public void clearCompleted() {
        jobs.values().removeIf(j ->
            j.getStatus() == JobStatus.COMPLETED || j.getStatus() == JobStatus.FAILED
        );
    }
}
