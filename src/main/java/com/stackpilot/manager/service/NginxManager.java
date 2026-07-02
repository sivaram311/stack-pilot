package com.stackpilot.manager.service;

import com.stackpilot.manager.config.StackPilotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class NginxManager {
    private static final Logger log = LoggerFactory.getLogger(NginxManager.class);
    private static final long COMMAND_TIMEOUT_SECONDS = 15;

    private final StackPilotProperties properties;
    private final ProcessInspector processInspector;
    private final ManagerActionLog actionLog;

    public NginxManager(StackPilotProperties properties, ProcessInspector processInspector, ManagerActionLog actionLog) {
        this.properties = properties;
        this.processInspector = processInspector;
        this.actionLog = actionLog;
    }

    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        List<Long> pids = findNginxPids();
        boolean running = !pids.isEmpty();

        status.put("running", running);
        status.put("pids", pids);
        status.put("port", properties.getNginx().getPort());
        status.put("portListening", processInspector.isPortInUse(properties.getNginx().getPort()));
        status.put("home", properties.getNginx().getHome());

        Map<String, Object> configTest = testConfig();
        status.put("configTest", configTest);
        status.put("healthChecks", runHealthChecks());
        status.put("upstreamChecks", upstreamChecks());

        return status;
    }

    public Map<String, Object> start() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!findNginxPids().isEmpty()) {
            result.put("success", true);
            result.put("message", "nginx is already running");
            actionLog.info("nginx start skipped: already running");
            return result;
        }

        Map<String, Object> configTest = testConfig();
        if (!Boolean.TRUE.equals(configTest.get("success"))) {
            result.put("success", false);
            result.put("message", "Configuration test failed");
            result.put("configTest", configTest);
            actionLog.error("nginx start blocked: config test failed");
            return result;
        }

        try {
            String nginxHome = properties.getNginx().getHome();
            String ps = "Start-Process -FilePath '.\\nginx.exe' -WorkingDirectory '" + escapePs(nginxHome)
                    + "' -WindowStyle Hidden";
            runPowerShell(ps);
            Thread.sleep(1000);

            List<Long> pids = findNginxPids();
            if (pids.isEmpty()) {
                result.put("success", false);
                result.put("message", "nginx failed to start. Check error.log");
                actionLog.error("nginx start failed: no process detected");
            } else {
                result.put("success", true);
                result.put("message", "nginx started");
                result.put("pids", pids);
                actionLog.info("nginx started (PIDs " + pids + ")");
            }
        } catch (Exception e) {
            log.error("Failed to start nginx", e);
            result.put("success", false);
            result.put("message", "Start failed: " + e.getMessage());
            actionLog.error("nginx start failed: " + e.getMessage());
        }
        return result;
    }

    public Map<String, Object> stop() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (findNginxPids().isEmpty()) {
            result.put("success", true);
            result.put("message", "nginx is not running");
            actionLog.info("nginx stop skipped: not running");
            return result;
        }

        try {
            runNginxCommand("-s", "quit");
            Thread.sleep(2000);

            List<Long> remaining = findNginxPids();
            if (!remaining.isEmpty()) {
                result.put("success", false);
                result.put("message", "nginx did not stop gracefully. Remaining PIDs: " + remaining);
                actionLog.warn("nginx stop incomplete: PIDs " + remaining);
            } else {
                result.put("success", true);
                result.put("message", "nginx stopped");
                actionLog.info("nginx stopped");
            }
        } catch (Exception e) {
            log.error("Failed to stop nginx", e);
            result.put("success", false);
            result.put("message", "Stop failed: " + e.getMessage());
            actionLog.error("nginx stop failed: " + e.getMessage());
        }
        return result;
    }

    public Map<String, Object> reload() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (findNginxPids().isEmpty()) {
            result.put("success", false);
            result.put("message", "nginx is not running. Start it first.");
            actionLog.warn("nginx reload blocked: not running");
            return result;
        }

        Map<String, Object> configTest = testConfig();
        if (!Boolean.TRUE.equals(configTest.get("success"))) {
            result.put("success", false);
            result.put("message", "Configuration test failed. Reload aborted.");
            result.put("configTest", configTest);
            actionLog.error("nginx reload blocked: config test failed");
            return result;
        }

        try {
            runNginxCommand("-s", "reload");
            result.put("success", true);
            result.put("message", "nginx configuration reloaded");
            actionLog.info("nginx configuration reloaded");
        } catch (Exception e) {
            log.error("Failed to reload nginx", e);
            result.put("success", false);
            result.put("message", "Reload failed: " + e.getMessage());
            actionLog.error("nginx reload failed: " + e.getMessage());
        }
        return result;
    }

    public List<String> getErrorLogTail(Integer tail) {
        return readLogTail(resolveLogPath(properties.getNginx().getErrorLog()), tail);
    }

    public List<String> getAccessLogTail(Integer tail) {
        return readLogTail(resolveLogPath(properties.getNginx().getAccessLog()), tail);
    }

    private Path resolveLogPath(String relativePath) {
        Path logPath = Paths.get(relativePath);
        if (logPath.isAbsolute()) {
            return logPath;
        }
        return Paths.get(properties.getNginx().getHome()).resolve(relativePath);
    }

    private List<String> readLogTail(Path path, Integer tail) {
        if (!Files.isRegularFile(path)) {
            return List.of("[StackPilot] Log file not found: " + path);
        }
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            int limit = tail != null && tail > 0 ? tail : 200;
            if (lines.size() <= limit) {
                return lines;
            }
            return lines.subList(lines.size() - limit, lines.size());
        } catch (IOException e) {
            return List.of("[StackPilot] Failed to read log: " + e.getMessage());
        }
    }

    private Map<String, Object> testConfig() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            CommandOutput output = runNginxCommand("-t");
            boolean success = output.exitCode == 0;
            result.put("success", success);
            result.put("output", output.combinedOutput());
            if (!success) {
                result.put("message", "Configuration test failed");
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            result.put("output", List.of(e.getMessage()));
        }
        return result;
    }

    private List<Map<String, Object>> runHealthChecks() {
        List<Map<String, Object>> checks = new ArrayList<>();
        for (StackPilotProperties.NginxSettings.HealthCheck check : properties.getNginx().getHealthChecks()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", check.getName());
            entry.put("url", check.getUrl());
            try {
                HttpURLConnection conn = (HttpURLConnection) URI.create(check.getUrl()).toURL().openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestMethod("GET");
                if (check.getHostHeader() != null && !check.getHostHeader().isBlank()) {
                    conn.setRequestProperty("Host", check.getHostHeader());
                }
                int code = conn.getResponseCode();
                entry.put("success", code >= 200 && code < 400);
                entry.put("statusCode", code);
            } catch (Exception e) {
                entry.put("success", false);
                entry.put("error", e.getMessage());
            }
            checks.add(entry);
        }
        return checks;
    }

    private Map<String, Object> upstreamChecks() {
        Map<String, Object> upstream = new LinkedHashMap<>();
        upstream.put("frontend4200", processInspector.isPortInUse(4200));
        upstream.put("stackPilot8091", processInspector.isPortInUse(8091));
        upstream.put("backend8081", processInspector.isPortInUse(8081));
        return upstream;
    }

    private List<Long> findNginxPids() {
        try {
            Process proc = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                    "Get-Process nginx -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Id").start();
            List<Long> pids = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        pids.add(Long.parseLong(line.trim()));
                    }
                }
            }
            waitForProcess(proc);
            return pids;
        } catch (Exception e) {
            log.debug("Failed to list nginx processes: {}", e.getMessage());
            return List.of();
        }
    }

    private CommandOutput runNginxCommand(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Paths.get(properties.getNginx().getHome(), "nginx.exe").toString());
        command.addAll(List.of(args));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(Paths.get(properties.getNginx().getHome()).toFile());
        pb.redirectErrorStream(true);

        Process proc = pb.start();
        List<String> output = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.add(line);
            }
        }
        waitForProcess(proc);
        return new CommandOutput(proc.exitValue(), output);
    }

    private void runPowerShell(String script) throws IOException, InterruptedException {
        Process proc = new ProcessBuilder("powershell", "-NoProfile", "-Command", script).start();
        waitForProcess(proc);
        if (proc.exitValue() != 0) {
            throw new IOException("PowerShell command failed with exit code " + proc.exitValue());
        }
    }

    private String escapePs(String value) {
        return value.replace("'", "''");
    }

    private void waitForProcess(Process proc) throws InterruptedException {
        if (!proc.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            throw new InterruptedException("Command timed out");
        }
    }

    private record CommandOutput(int exitCode, List<String> lines) {
        List<String> combinedOutput() {
            return lines == null ? List.of() : lines;
        }
    }
}
