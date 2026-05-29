package com.threadconv.converter;

import com.threadconv.model.Job;
import com.threadconv.model.JobStatus;
import com.threadconv.service.JobStoreService;
import com.threadconv.service.SocketService;

import java.util.Set;

/**
 * Runnable submitted to the WorkerPoolService executor for each job.
 *
 * Routing logic mirrors worker.js: detect file type from extension, delegate
 * to the appropriate converter.  Progress is reported via SocketService so the
 * React frontend gets live updates.
 *
 * This class is NOT a Spring bean — a new instance is created per job inside
 * WorkerPoolService, which injects its dependencies via constructor.
 */
public class ConversionTask implements Runnable {

    private static final Set<String> IMAGE_EXTS =
        Set.of("jpg", "jpeg", "png", "webp", "avif", "gif", "tiff");
    private static final Set<String> TEXT_EXTS =
        Set.of("txt", "md", "json", "html", "csv", "xml");
    private static final Set<String> VIDEO_EXTS =
        Set.of("mp4", "mkv", "avi", "mov", "webm", "flv", "wmv");

    private final Job             job;
    private final JobStoreService jobStore;
    private final SocketService   sockets;
    private final String          ffmpegPath;
    private final Runnable        onSuccess;
    private final Runnable        onFailure;

    public ConversionTask(Job job,
                          JobStoreService jobStore,
                          SocketService sockets,
                          String ffmpegPath,
                          Runnable onSuccess,
                          Runnable onFailure) {
        this.job        = job;
        this.jobStore   = jobStore;
        this.sockets    = sockets;
        this.ffmpegPath = ffmpegPath;
        this.onSuccess  = onSuccess;
        this.onFailure  = onFailure;
    }

    @Override
    public void run() {
        // Mark processing
        job.setStatus(JobStatus.PROCESSING);
        job.setProgressMessage("Starting conversion...");
        sockets.emitProcessing(job.getJobId());

        ProgressCallback progress = (pct, msg) -> {
            job.setProgress(pct);
            job.setProgressMessage(msg);
            sockets.emitProgress(job.getJobId(), pct, msg);
        };

        try {
            progress.report(5, "Starting conversion...");
            String ext = extension(job.getOriginalName());

            if (IMAGE_EXTS.contains(ext)) {
                ImageConverter.convert(ffmpegPath,
                    job.getInputPath(), job.getOutputPath(), job.getTargetFormat(), progress);

            } else if (TEXT_EXTS.contains(ext)) {
                TextConverter.convert(
                    job.getInputPath(), job.getOutputPath(), job.getTargetFormat(), progress);

            } else if (VIDEO_EXTS.contains(ext)) {
                VideoConverter.convert(ffmpegPath,
                    job.getInputPath(), job.getOutputPath(), job.getTargetFormat(), progress);

            } else {
                throw new RuntimeException(
                    "Conversion from ." + ext + " to ." + job.getTargetFormat() + " is not supported.");
            }

            // Success
            job.setStatus(JobStatus.COMPLETED);
            job.setProgress(100);
            job.setCompletedAt(System.currentTimeMillis());
            sockets.emitDone(job.getJobId());
            onSuccess.run();

        } catch (Exception e) {
            job.setError(e.getMessage());
            job.setStatus(JobStatus.FAILED);
            job.setCompletedAt(System.currentTimeMillis());
            sockets.emitFailed(job.getJobId(), e.getMessage());
            onFailure.run();
        }
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1).toLowerCase() : "";
    }
}
