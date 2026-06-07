package com.threadconv.worker.model;

/**
 * Job dispatch payload sent from the API service to this worker over HTTP.
 *
 * inputPath and outputPath are absolute so the worker can find the files
 * regardless of its working directory.
 *
 * callbackBaseUrl is the API's origin (e.g. "http://localhost:3001"); the
 * worker posts progress, done, and failed callbacks to that base.
 */
public class WorkRequest {
    public String jobId;
    public String inputPath;
    public String outputPath;
    public String originalName;
    public String targetFormat;
    public String callbackBaseUrl;
}
