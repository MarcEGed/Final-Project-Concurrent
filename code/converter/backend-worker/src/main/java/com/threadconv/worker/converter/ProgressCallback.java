package com.threadconv.worker.converter;

@FunctionalInterface
public interface ProgressCallback {
    void report(int percent, String message);
}
