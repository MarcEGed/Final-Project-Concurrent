package com.threadconv;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * THREADCONV — Multithreaded File Conversion Service
 * Java 21 + Spring Boot 3 + Gradle
 *
 * Entry point. @EnableScheduling activates the 15-minute cleanup cron
 * in WorkerPoolService.
 */
@SpringBootApplication
@EnableScheduling
public class ThreadConvApplication {

    public static void main(String[] args) {
        SpringApplication.run(ThreadConvApplication.class, args);
    }
}
