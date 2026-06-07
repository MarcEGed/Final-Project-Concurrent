package com.threadconv.worker.converter;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class ImageConverter {

    private static final int TIMEOUT_MINUTES = 2;

    public static void convert(String ffmpegPath,
                               String inputPath, String outputPath,
                               String targetFormat,
                               ProgressCallback progress)
            throws IOException, InterruptedException {

        progress.report(10, "Reading image...");

        ProcessBuilder pb = new ProcessBuilder(ffmpegPath, "-y", "-i", inputPath, outputPath);
        pb.redirectErrorStream(true);
        Process proc = pb.start();

        Thread drainer = new Thread(() -> {
            try { proc.getInputStream().transferTo(java.io.OutputStream.nullOutputStream()); }
            catch (IOException ignored) {}
        });
        drainer.setDaemon(true);
        drainer.start();

        progress.report(40, "Converting to " + targetFormat.toUpperCase() + "...");

        boolean finished = proc.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);
        if (!finished) {
            proc.destroyForcibly();
            throw new RuntimeException("Image conversion timed out after " + TIMEOUT_MINUTES + " minutes");
        }
        if (proc.exitValue() != 0)
            throw new RuntimeException("FFmpeg exited with code " + proc.exitValue()
                    + " converting image to " + targetFormat);

        progress.report(100, "Done");
    }
}
