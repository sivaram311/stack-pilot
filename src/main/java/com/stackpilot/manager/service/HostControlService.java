package com.stackpilot.manager.service;

import com.stackpilot.manager.config.StackPilotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class HostControlService {
    private static final Logger log = LoggerFactory.getLogger(HostControlService.class);
    private static final long COMMAND_TIMEOUT_SECONDS = 10;

    private final StackPilotProperties properties;
    private final ManagerActionLog actionLog;

    private volatile String pendingAction;
    private volatile long pendingScheduledAtMs;
    private volatile int pendingDelaySeconds;

    public HostControlService(StackPilotProperties properties, ManagerActionLog actionLog) {
        this.properties = properties;
        this.actionLog = actionLog;
    }

    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", properties.getHost().isEnabled());
        status.put("shutdownDelaySeconds", properties.getHost().getShutdownDelaySeconds());
        status.put("confirmPhraseRestart", properties.getHost().getConfirmPhraseRestart());
        status.put("confirmPhraseShutdown", properties.getHost().getConfirmPhraseShutdown());
        status.put("pending", getPendingStatus());
        return status;
    }

    public Map<String, Object> scheduleRestart(String confirmPhrase) {
        return scheduleAction("RESTART", properties.getHost().getConfirmPhraseRestart(), confirmPhrase, "/r");
    }

    public Map<String, Object> scheduleShutdown(String confirmPhrase) {
        return scheduleAction("SHUTDOWN", properties.getHost().getConfirmPhraseShutdown(), confirmPhrase, "/s");
    }

    public Map<String, Object> cancelPending() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!properties.getHost().isEnabled()) {
            result.put("success", false);
            result.put("message", "Host controls are disabled");
            return result;
        }

        try {
            Process proc = new ProcessBuilder("shutdown", "/a").start();
            waitForProcess(proc);
            clearPending();
            result.put("success", true);
            result.put("message", "Pending host action cancelled");
            actionLog.info("Host action cancelled");
        } catch (Exception e) {
            log.error("Failed to cancel shutdown", e);
            result.put("success", false);
            result.put("message", "Cancel failed: " + e.getMessage());
            actionLog.error("Host cancel failed: " + e.getMessage());
        }
        return result;
    }

    public Map<String, Object> getPendingStatus() {
        Map<String, Object> pending = new LinkedHashMap<>();
        if (pendingAction == null) {
            pending.put("active", false);
            return pending;
        }

        long elapsedMs = System.currentTimeMillis() - pendingScheduledAtMs;
        long remainingSeconds = Math.max(0, pendingDelaySeconds - (elapsedMs / 1000));

        pending.put("active", true);
        pending.put("action", pendingAction);
        pending.put("delaySeconds", pendingDelaySeconds);
        pending.put("remainingSeconds", remainingSeconds);
        pending.put("scheduledAtMs", pendingScheduledAtMs);
        return pending;
    }

    private Map<String, Object> scheduleAction(String action, String expectedPhrase, String confirmPhrase, String flag) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!properties.getHost().isEnabled()) {
            result.put("success", false);
            result.put("message", "Host controls are disabled in configuration");
            return result;
        }

        if (confirmPhrase == null || !confirmPhrase.trim().equals(expectedPhrase)) {
            result.put("success", false);
            result.put("message", "Confirmation phrase does not match");
            actionLog.warn("Host " + action.toLowerCase() + " blocked: invalid confirmation phrase");
            return result;
        }

        int delay = properties.getHost().getShutdownDelaySeconds();
        String comment = "Stack Pilot initiated " + action.toLowerCase();

        try {
            Process proc = new ProcessBuilder(
                    "shutdown",
                    flag,
                    "/t", String.valueOf(delay),
                    "/c", comment
            ).start();

            String output = readProcessOutput(proc);
            waitForProcess(proc);

            if (proc.exitValue() != 0) {
                result.put("success", false);
                result.put("message", "shutdown command failed: " + output.trim());
                actionLog.error("Host " + action.toLowerCase() + " failed: " + output.trim());
                return result;
            }

            pendingAction = action;
            pendingScheduledAtMs = System.currentTimeMillis();
            pendingDelaySeconds = delay;

            result.put("success", true);
            result.put("message", action + " scheduled in " + delay + " seconds");
            result.put("delaySeconds", delay);
            result.put("pending", getPendingStatus());
            actionLog.warn("Host " + action.toLowerCase() + " scheduled in " + delay + "s");
        } catch (Exception e) {
            log.error("Failed to schedule host {}", action, e);
            result.put("success", false);
            result.put("message", "Failed to schedule " + action.toLowerCase() + ": " + e.getMessage());
            actionLog.error("Host " + action.toLowerCase() + " failed: " + e.getMessage());
        }
        return result;
    }

    private void clearPending() {
        pendingAction = null;
        pendingScheduledAtMs = 0;
        pendingDelaySeconds = 0;
    }

    private String readProcessOutput(Process proc) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append(System.lineSeparator());
            }
        }
        return sb.toString();
    }

    private void waitForProcess(Process proc) throws InterruptedException {
        if (!proc.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            throw new InterruptedException("Command timed out");
        }
    }
}
