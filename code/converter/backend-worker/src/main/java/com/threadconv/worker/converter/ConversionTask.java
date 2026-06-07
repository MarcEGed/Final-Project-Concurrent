package com.threadconv.worker.converter;

import com.threadconv.worker.ApiCallbackClient;
import com.threadconv.worker.model.WorkRequest;

import java.util.Set;

/**
 * Runnable executed inside the worker's ThreadPoolExecutor.
 *
 * On every progress tick it calls ApiCallbackClient.reportProgress() which
 * POSTs to the API service over HTTP.  On completion it calls reportDone()
 * or reportFailed(), which the API uses to update the job store and push
 * a Socket.IO event to the browser.
 *
 * The jobId carried in every callback is the distributed correlation ID —
 * it ties together the API log, the worker log, and the browser event.
 */
public class ConversionTask implements Runnable {

    private static final Set<String> IMAGE_EXTS =
            Set.of("jpg", "jpeg", "png", "webp", "avif", "gif", "tiff");
    private static final Set<String> TEXT_EXTS =
            Set.of("txt", "md", "json", "html", "csv", "xml");
    private static final Set<String> VIDEO_EXTS =
            Set.of("mp4", "mkv", "avi", "mov", "webm", "flv", "wmv");

    private final WorkRequest       req;
    private final String            ffmpegPath;
    private final ApiCallbackClient callback;
    private final Runnable          onSuccess;
    private final Runnable          onFailure;

    public ConversionTask(WorkRequest req, String ffmpegPath,
                          ApiCallbackClient callback,
                          Runnable onSuccess, Runnable onFailure) {
        this.req        = req;
        this.ffmpegPath = ffmpegPath;
        this.callback   = callback;
        this.onSuccess  = onSuccess;
        this.onFailure  = onFailure;
    }

    @Override
    public void run() {
        System.out.printf("[Worker] START  jobId=%s  file=%s -> .%s%n",
                req.jobId, req.originalName, req.targetFormat);

        ProgressCallback progress = (pct, msg) -> {
            System.out.printf("[Worker] PROG   jobId=%s  %3d%%  %s%n", req.jobId, pct, msg);
            callback.reportProgress(req.callbackBaseUrl, req.jobId, pct, msg);
        };

        try {
            String ext = extension(req.originalName);

            if (IMAGE_EXTS.contains(ext)) {
                ImageConverter.convert(ffmpegPath, req.inputPath, req.outputPath, req.targetFormat, progress);
            } else if (TEXT_EXTS.contains(ext)) {
                TextConverter.convert(req.inputPath, req.outputPath, req.targetFormat, progress);
            } else if (VIDEO_EXTS.contains(ext)) {
                VideoConverter.convert(ffmpegPath, req.inputPath, req.outputPath, req.targetFormat, progress);
            } else {
                throw new RuntimeException("Unsupported: ." + ext + " -> ." + req.targetFormat);
            }

            System.out.printf("[Worker] DONE   jobId=%s%n", req.jobId);
            callback.reportDone(req.callbackBaseUrl, req.jobId);
            onSuccess.run();

        } catch (Exception e) {
            System.out.printf("[Worker] FAILED jobId=%s  error=%s%n", req.jobId, e.getMessage());
            callback.reportFailed(req.callbackBaseUrl, req.jobId, e.getMessage());
            onFailure.run();
        }
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1).toLowerCase() : "";
    }
}
