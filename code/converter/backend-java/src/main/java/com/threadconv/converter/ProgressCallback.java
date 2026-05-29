package com.threadconv.converter;

/** Passed into each converter so it can report progress without knowing about sockets. */
@FunctionalInterface
public interface ProgressCallback {
    void report(int percent, String message);
}
