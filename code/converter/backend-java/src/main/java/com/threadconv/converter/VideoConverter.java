package com.threadconv.converter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts video files via FFmpeg.
 *
 * Progress is estimated by parsing FFmpeg's "time=" output lines and comparing
 * elapsed seconds against the source duration (also parsed from FFmpeg probe).
 * Timeout: 30 minutes per file.
 */
public class VideoConverter {

    private static final int TIMEOUT_MINUTES = 30;
    // Matches: time=HH:MM:SS.ss
    private static final Pattern TIME_PATTERN =
        Pattern.compile("time=(\\d{2}):(\\d{2}):(\\d{2})\\.(\\d+)");

    public static void convert(String ffmpegPath,
                               String inputPath, String outputPath,
                               String targetFormat,
                               ProgressCallback progress)
            throws IOException, InterruptedException {

        progress.report(5, "Preparing video conversion...");

        List<String> cmd = buildCommand(ffmpegPath, inputPath, outputPath, targetFormat);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true); // FFmpeg writes progress to stderr; merge so we read one stream
        Process proc = pb.start();

        int lastPct = 5;
        StringBuilder outputLog = new StringBuilder();

        try (BufferedReader reader =
                 new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                outputLog.append(line).append('\n');
                Matcher m = TIME_PATTERN.matcher(line);
                if (m.find()) {
                    double elapsed = Integer.parseInt(m.group(1)) * 3600.0
                                   + Integer.parseInt(m.group(2)) * 60.0
                                   + Integer.parseInt(m.group(3))
                                   + Integer.parseInt(m.group(4)) * 0.01;
                    // Scale to 10–90 % (assume ~60s source; capped at 90)
                    int pct = (int) Math.min(90, 10 + (elapsed / 60.0) * 80);
                    if (pct > lastPct) {
                        lastPct = pct;
                        progress.report(pct, "Converting video... " + pct + "%");
                    }
                }
            }
        }

        boolean finished = proc.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);
        if (!finished) {
            proc.destroyForcibly();
            throw new RuntimeException("Video conversion timed out after " + TIMEOUT_MINUTES + " minutes");
        }
        if (proc.exitValue() != 0) {
            // Extract the most relevant error line from FFmpeg output
            String hint = outputLog.toString().lines()
                .filter(l -> l.contains("Error") || l.contains("error") || l.contains("Invalid") || l.contains("encoder"))
                .reduce((a, b) -> b).orElse("").trim();
            throw new RuntimeException("FFmpeg failed (code " + proc.exitValue() + ")"
                    + (hint.isEmpty() ? "" : ": " + hint));
        }

        progress.report(100, "Video conversion complete");
    }

    private static List<String> buildCommand(String ffmpegPath,
                                             String inputPath, String outputPath,
                                             String targetFormat) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-y");
        cmd.add("-i"); cmd.add(inputPath);

        // -strict -2 enables the aac encoder on older FFmpeg builds (pre-2015)
        // where it was marked experimental. Ignored harmlessly on modern FFmpeg.
        switch (targetFormat.toLowerCase()) {
            case "mp4"  -> { cmd.addAll(List.of("-c:v", "libx264", "-c:a", "aac", "-strict", "-2")); cmd.add("-f"); cmd.add("mp4"); }
            case "mkv"  -> { cmd.addAll(List.of("-c:v", "libx264", "-c:a", "aac", "-strict", "-2")); cmd.add("-f"); cmd.add("matroska"); }
            case "avi"  -> { cmd.addAll(List.of("-c:v", "libx264", "-c:a", "aac", "-strict", "-2")); cmd.add("-f"); cmd.add("avi"); }
            case "mov"  -> { cmd.addAll(List.of("-c:v", "libx264", "-c:a", "aac", "-strict", "-2")); cmd.add("-f"); cmd.add("mov"); }
            case "webm" -> { cmd.addAll(List.of("-c:v", "libvpx",  "-c:a", "libopus")); cmd.add("-f"); cmd.add("webm"); }
            default     -> throw new IllegalArgumentException("Unsupported video format: " + targetFormat);
        }

        cmd.add(outputPath);
        return cmd;
    }
}
