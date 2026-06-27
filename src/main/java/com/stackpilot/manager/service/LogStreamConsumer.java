package com.stackpilot.manager.service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LogStreamConsumer implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(LogStreamConsumer.class);

    private final InputStream inputStream;
    private final String serviceName;
    private final File logFile;
    private final int maxInMemoryLogLines;
    private final Queue<String> inMemoryLogs = new ConcurrentLinkedQueue<>();

    public LogStreamConsumer(
            InputStream inputStream,
            String serviceName,
            Path logsDir,
            String logFileName,
            int maxInMemoryLogLines) {
        this.inputStream = inputStream;
        this.serviceName = serviceName;
        this.maxInMemoryLogLines = maxInMemoryLogLines;

        try {
            Files.createDirectories(logsDir);
        } catch (IOException e) {
            log.error("Failed to create logs directory: {}", logsDir, e);
        }
        this.logFile = logsDir.resolve(logFileName).toFile();
    }

    @Override
    public void run() {
        log.info("Starting log consumer for service: {} -> {}", serviceName, logFile.getAbsolutePath());

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
             PrintWriter fileWriter = new PrintWriter(
                     new OutputStreamWriter(new FileOutputStream(logFile, true), StandardCharsets.UTF_8), true)) {

            String line;
            while ((line = reader.readLine()) != null) {
                log.info("[{}] {}", serviceName, line);
                fileWriter.println(line);
                addLogLine(line);
            }
        } catch (IOException e) {
            String msg = "Stream closed or error in log reader for " + serviceName + ": " + e.getMessage();
            log.warn(msg);
            addLogLine("[MANAGER-ERROR] " + msg);
        }
        log.info("Finished log consumer for service: {}", serviceName);
    }

    private void addLogLine(String line) {
        inMemoryLogs.add(line);
        while (inMemoryLogs.size() > maxInMemoryLogLines) {
            inMemoryLogs.poll();
        }
    }

    public Queue<String> getInMemoryLogs() {
        return inMemoryLogs;
    }

    public void clearLogs() {
        inMemoryLogs.clear();
    }
}
