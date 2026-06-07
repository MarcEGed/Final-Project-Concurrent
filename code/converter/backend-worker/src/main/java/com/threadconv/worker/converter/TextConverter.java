package com.threadconv.worker.converter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class TextConverter {

    public static void convert(String inputPath, String outputPath,
                               String targetFormat,
                               ProgressCallback progress) throws IOException {

        progress.report(20, "Reading file...");
        String content = Files.readString(Path.of(inputPath), StandardCharsets.UTF_8);

        progress.report(60, "Converting...");

        String result;
        switch (targetFormat.toLowerCase()) {
            case "txt", "md" -> result = content;
            case "json" -> {
                String escaped = content
                        .replace("\\", "\\\\").replace("\"", "\\\"")
                        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
                String filename = Path.of(inputPath).getFileName().toString();
                result = "{\n  \"content\": \"" + escaped + "\",\n  \"source\": \"" + filename + "\"\n}";
            }
            case "html" -> {
                String escaped = content
                        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
                result = "<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\">"
                        + "<title>Converted</title></head>"
                        + "<body><pre>" + escaped + "</pre></body></html>";
            }
            default -> throw new RuntimeException("Unsupported text target format: " + targetFormat);
        }

        progress.report(90, "Saving output...");
        Files.writeString(Path.of(outputPath), result, StandardCharsets.UTF_8);
        progress.report(100, "Done");
    }
}
