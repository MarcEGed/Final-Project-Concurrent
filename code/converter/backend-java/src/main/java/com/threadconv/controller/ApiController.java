package com.threadconv.controller;

import com.threadconv.model.Job;
import com.threadconv.model.JobStatus;
import com.threadconv.service.JobStoreService;
import com.threadconv.service.WorkerPoolService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * REST API — same endpoints as the Node.js backend so the React frontend and
 * stress-advanced.js work without changes.
 *
 *  POST   /api/upload          — upload files, create jobs
 *  GET    /api/jobs            — all jobs
 *  GET    /api/jobs/{jobId}    — single job
 *  GET    /api/download/{id}   — download converted file
 *  GET    /api/download-zip    — batch ZIP download
 *  GET    /api/stats           — worker pool metrics
 *  DELETE /api/jobs/clear      — clear completed / failed jobs
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")  // allow the React dev server
public class ApiController {

    @Value("${app.upload-dir:./uploads}")
    private String uploadDir;

    @Value("${app.output-dir:./outputs}")
    private String outputDir;

    private final JobStoreService  jobStore;
    private final WorkerPoolService workerPool;

    public ApiController(JobStoreService jobStore, WorkerPoolService workerPool) {
        this.jobStore   = jobStore;
        this.workerPool = workerPool;
    }

    // ── POST /api/upload ─────────────────────────────────────────────────────
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam("targetFormat") String targetFormat) throws IOException {

        if (files == null || files.length == 0)
            return ResponseEntity.badRequest().body(Map.of("error", "No files uploaded"));
        if (targetFormat == null || targetFormat.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "targetFormat is required"));

        Files.createDirectories(Path.of(uploadDir));
        Files.createDirectories(Path.of(outputDir));

        List<Map<String, Object>> created = new ArrayList<>();

        for (MultipartFile file : files) {
            // ── Deduplication: skip re-conversion of an identical file ────────
            byte[] bytes = file.getBytes();
            String contentKey = sha256Hex(bytes) + ":" + targetFormat;

            Job existing = jobStore.findActiveByContentKey(contentKey);
            if (existing != null) {
                System.out.printf("[Upload] Duplicate detected — reusing job %s for %s%n",
                        existing.getJobId(), file.getOriginalFilename());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("jobId",        existing.getJobId());
                row.put("originalName", file.getOriginalFilename());
                row.put("status",       existing.getStatus().name().toLowerCase());
                row.put("deduplicated", true);
                created.add(row);
                continue;
            }

            // ── New job ───────────────────────────────────────────────────────
            String jobId          = UUID.randomUUID().toString();
            String outputFilename = jobId + "." + targetFormat;
            String inputPath      = uploadDir + "/" + jobId + "-" + file.getOriginalFilename();
            String outputPath     = outputDir + "/" + outputFilename;

            // Save upload to disk (bytes already in memory from hash computation)
            Files.write(Path.of(inputPath), bytes);

            Job job = new Job(jobId, file.getOriginalFilename(), inputPath,
                              outputPath, outputFilename, targetFormat, file.getSize(), contentKey);
            jobStore.put(job);

            boolean accepted = workerPool.submit(job);
            if (!accepted) {
                // Queue full — job is already marked FAILED inside WorkerPoolService
                created.add(Map.of(
                    "jobId",        jobId,
                    "originalName", file.getOriginalFilename(),
                    "status",       "failed"
                ));
            } else {
                created.add(Map.of(
                    "jobId",        jobId,
                    "originalName", file.getOriginalFilename(),
                    "status",       "queued"
                ));
            }
        }

        return ResponseEntity.ok(Map.of("jobs", created));
    }

    // ── GET /api/jobs ─────────────────────────────────────────────────────────
    @GetMapping("/jobs")
    public ResponseEntity<Map<String, Object>> listJobs() {
        List<Map<String, Object>> list = jobStore.getAll().stream()
            .map(ApiController::jobToMap)
            .toList();
        return ResponseEntity.ok(Map.of("jobs", list));
    }

    // ── GET /api/jobs/{jobId} ─────────────────────────────────────────────────
    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<?> getJob(@PathVariable String jobId) {
        Job job = jobStore.get(jobId);
        if (job == null)
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Job not found"));
        return ResponseEntity.ok(jobToMap(job));
    }

    // ── GET /api/download/{jobId} ─────────────────────────────────────────────
    @GetMapping("/download/{jobId}")
    public void download(@PathVariable String jobId, HttpServletResponse resp) throws IOException {
        Job job = jobStore.get(jobId);
        if (job == null || job.getStatus() != JobStatus.COMPLETED) {
            resp.sendError(HttpStatus.NOT_FOUND.value(), "File not ready");
            return;
        }

        Path path = Path.of(job.getOutputPath());
        if (!Files.exists(path)) {
            resp.sendError(HttpStatus.NOT_FOUND.value(), "Output file missing");
            return;
        }

        String downloadName = "converted-"
            + job.getOriginalName().replaceAll("\\.[^.]+$", "")
            + "." + job.getTargetFormat();

        resp.setContentType("application/octet-stream");
        resp.setHeader("Content-Disposition", "attachment; filename=\"" + downloadName + "\"");
        resp.setContentLengthLong(Files.size(path));

        try (InputStream in = Files.newInputStream(path)) {
            in.transferTo(resp.getOutputStream());
        }
    }

    // ── GET /api/download-zip?ids=id1,id2 ────────────────────────────────────
    @GetMapping("/download-zip")
    public void downloadZip(@RequestParam("ids") String idsParam,
                            HttpServletResponse resp) throws IOException {

        List<Job> readyJobs = Arrays.stream(idsParam.split(","))
            .map(String::trim)
            .filter(id -> !id.isBlank())
            .map(jobStore::get)
            .filter(j -> j != null
                      && j.getStatus() == JobStatus.COMPLETED
                      && Files.exists(Path.of(j.getOutputPath())))
            .toList();

        if (readyJobs.isEmpty()) {
            resp.sendError(HttpStatus.BAD_REQUEST.value(), "No completed files found");
            return;
        }

        resp.setContentType("application/zip");
        resp.setHeader("Content-Disposition", "attachment; filename=\"converted-files.zip\"");

        try (ZipOutputStream zip = new ZipOutputStream(resp.getOutputStream())) {
            for (Job job : readyJobs) {
                String entryName = "converted-"
                    + job.getOriginalName().replaceAll("\\.[^.]+$", "")
                    + "." + job.getTargetFormat();
                zip.putNextEntry(new ZipEntry(entryName));
                try (InputStream in = Files.newInputStream(Path.of(job.getOutputPath()))) {
                    in.transferTo(zip);
                }
                zip.closeEntry();
            }
        }
    }

    // ── GET /api/stats ────────────────────────────────────────────────────────
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        return ResponseEntity.ok(workerPool.getStats());
    }

    // ── DELETE /api/jobs/clear ────────────────────────────────────────────────
    @DeleteMapping("/jobs/clear")
    public ResponseEntity<Map<String, Object>> clearJobs() {
        jobStore.clearCompleted();
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String sha256Hex(byte[] data) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Map<String, Object> jobToMap(Job j) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jobId",           j.getJobId());
        m.put("originalName",    j.getOriginalName());
        m.put("targetFormat",    j.getTargetFormat());
        m.put("status",          j.getStatus().name().toLowerCase());
        m.put("progress",        j.getProgress());
        m.put("progressMessage", j.getProgressMessage());
        m.put("error",           j.getError());
        m.put("outputFilename",  j.getOutputFilename());
        m.put("fileSize",        j.getFileSize());
        m.put("createdAt",       j.getCreatedAt());
        m.put("completedAt",     j.getCompletedAt() > 0 ? j.getCompletedAt() : null);
        return m;
    }
}
