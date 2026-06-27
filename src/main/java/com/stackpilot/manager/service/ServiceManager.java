package com.stackpilot.manager.service;

import com.stackpilot.manager.config.StackPilotProperties;
import com.stackpilot.manager.model.ServiceInfo;
import com.stackpilot.manager.model.ServiceStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;

@Service
public class ServiceManager {
    private static final Logger log = LoggerFactory.getLogger(ServiceManager.class);

    private final StackPilotProperties properties;
    private final ProcessInspector processInspector;
    private final ManagerActionLog actionLog;
    private final Map<String, ServiceInfo> servicesMap = new ConcurrentHashMap<>();
    private final Map<String, Process> processesMap = new ConcurrentHashMap<>();
    private final Map<String, LogStreamConsumer> logConsumersMap = new ConcurrentHashMap<>();
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private final Map<String, ExternalScanCache> externalScanCache = new ConcurrentHashMap<>();
    private Path logsDirectory;

    private static final class ExternalScanCache {
        private final long scannedAtMs;
        private final OptionalLong result;

        private ExternalScanCache(long scannedAtMs, OptionalLong result) {
            this.scannedAtMs = scannedAtMs;
            this.result = result;
        }
    }

    public ServiceManager(StackPilotProperties properties, ProcessInspector processInspector, ManagerActionLog actionLog) {
        this.properties = properties;
        this.processInspector = processInspector;
        this.actionLog = actionLog;
    }

    @PostConstruct
    public void init() {
        Path logsDirPath = Paths.get(properties.getLogsDir());
        logsDirectory = logsDirPath.isAbsolute()
                ? logsDirPath
                : Paths.get(System.getProperty("user.dir")).resolve(logsDirPath);

        for (Map.Entry<String, StackPilotProperties.ServiceDefinition> entry : properties.getServices().entrySet()) {
            String name = entry.getKey();
            StackPilotProperties.ServiceDefinition def = entry.getValue();

            servicesMap.put(name, new ServiceInfo(
                    name,
                    def.getWorkingDir(),
                    def.getCommand(),
                    ServiceStatus.STOPPED,
                    null,
                    null,
                    def.getPort(),
                    false));
        }

        log.info("Initialized stack-pilot with {} service(s). Logs directory: {}", servicesMap.size(), logsDirectory);
    }

    public synchronized List<ServiceInfo> getServices() {
        List<ServiceInfo> list = new ArrayList<>();
        for (String name : servicesMap.keySet()) {
            updateServiceStatus(name);
            list.add(servicesMap.get(name));
        }
        list.sort(Comparator.comparing(ServiceInfo::getName));
        return list;
    }

    public synchronized ServiceInfo getService(String name) {
        if (!servicesMap.containsKey(name)) {
            return null;
        }
        updateServiceStatus(name);
        return servicesMap.get(name);
    }

    public synchronized boolean startService(String name) {
        if (!servicesMap.containsKey(name)) {
            log.error("Service not found: {}", name);
            return false;
        }

        updateServiceStatus(name);
        ServiceInfo info = servicesMap.get(name);

        if (info.getStatus() == ServiceStatus.RUNNING || info.getStatus() == ServiceStatus.STARTING) {
            log.warn("Service {} is already running under StackPilot.", name);
            return true;
        }

        OptionalLong externalPid = findExternalProcess(name);
        if (externalPid.isPresent()) {
            long pid = externalPid.getAsLong();
            info.setStatus(ServiceStatus.RUNNING_EXTERNAL);
            info.setExternal(true);
            info.setPid(pid);
            info.setErrorMessage(buildExternalBlockMessage(name, pid));
            log.warn("Blocked start for {}: external process already running (PID {})", name, pid);
            actionLog.warn("Blocked start for " + name + ": external PID " + pid);
            return false;
        }

        log.info("Starting service: {} in dir: {}", name, info.getWorkingDir());
        info.setStatus(ServiceStatus.STARTING);
        info.setErrorMessage(null);
        info.setExternal(false);

        try {
            StackPilotProperties.ServiceDefinition def = properties.getServices().get(name);
            ProcessBuilder pb = buildProcessBuilder(def, info.getCommand());
            Process process = pb.start();
            long pid = process.pid();
            info.setPid(pid);
            info.setStatus(ServiceStatus.RUNNING);
            info.setExternal(false);
            processesMap.put(name, process);

            String logFileName = def.getLogFile() != null ? def.getLogFile() : name + ".log";

            LogStreamConsumer consumer = new LogStreamConsumer(
                    process.getInputStream(),
                    name,
                    logsDirectory,
                    logFileName,
                    properties.getMaxLogLines());
            logConsumersMap.put(name, consumer);
            executorService.submit(consumer);

            log.info("Successfully started service {} with PID {}", name, pid);
            actionLog.info("Started " + name + " (PID " + pid + ")");
            invalidateExternalCache(name);
            return true;
        } catch (IOException e) {
            log.error("Failed to start service: {}", name, e);
            actionLog.error("Failed to start " + name + ": " + e.getMessage());
            info.setStatus(ServiceStatus.ERROR);
            info.setErrorMessage("Launch failed: " + e.getMessage());
            info.setPid(null);
            info.setExternal(false);
            return false;
        }
    }

    public synchronized boolean stopService(String name) {
        if (!servicesMap.containsKey(name)) {
            log.error("Service not found: {}", name);
            return false;
        }

        updateServiceStatus(name);
        ServiceInfo info = servicesMap.get(name);
        Process process = processesMap.get(name);

        if (process != null) {
            log.info("Stopping managed service: {}", name);
            killProcessTree(process);
            processesMap.remove(name);
            logConsumersMap.remove(name);
        }

        killAllExternalMatches(name);

        StackPilotProperties.ServiceDefinition def = properties.getServices().get(name);
        if (def != null && def.getPort() != null && processInspector.isPortInUse(def.getPort())) {
            processInspector.findListeningPid(def.getPort()).ifPresent(pid -> {
                log.info("Stopping listener on port {} for {} (PID {})", def.getPort(), name, pid);
                processInspector.killProcessTree(pid);
            });
        }

        if (process == null && info.getStatus() == ServiceStatus.STOPPED && !hasExternalPresence(name)) {
            log.warn("Service {} is already stopped.", name);
            return true;
        }

        info.setStatus(ServiceStatus.STOPPED);
        info.setPid(null);
        info.setExternal(false);
        info.setErrorMessage(null);
        log.info("Stopped service: {}", name);
        actionLog.info("Stopped " + name);
        invalidateExternalCache(name);
        return true;
    }

    public synchronized boolean restartService(String name) {
        log.info("Restarting service: {}", name);
        actionLog.info("Restart / take control requested for " + name);
        stopService(name);

        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        refreshExternalStatus(name);
        return startService(name);
    }

    /** Kill external or managed instance, then start under StackPilot. */
    public synchronized boolean takeoverService(String name) {
        return restartService(name);
    }

    public synchronized Map<String, Object> stopAllServices() {
        Map<String, Object> results = new LinkedHashMap<>();
        for (String name : stopOrder()) {
            boolean ok = stopService(name);
            results.put(name, actionResult(name, ok));
        }
        return wrapBulkResult(results);
    }

    public synchronized Map<String, Object> startAllServices() {
        Map<String, Object> results = new LinkedHashMap<>();
        for (String name : startOrder()) {
            boolean ok = startService(name);
            results.put(name, actionResult(name, ok));
            pauseBetweenServices();
        }
        return wrapBulkResult(results);
    }

    /** Stop every service (including external), then start all under StackPilot. */
    public synchronized Map<String, Object> restartAllServices() {
        actionLog.info("Restart All (take control) started");
        invalidateAllExternalCaches();
        Map<String, Object> stopResults = new LinkedHashMap<>();
        for (String name : stopOrder()) {
            boolean ok = stopService(name);
            stopResults.put(name, actionResult(name, ok));
        }

        pause(2000);

        Map<String, Object> startResults = new LinkedHashMap<>();
        for (String name : startOrder()) {
            refreshExternalStatus(name);
            boolean ok = startService(name);
            startResults.put(name, actionResult(name, ok));
            pauseBetweenServices();
        }

        Map<String, Object> response = wrapBulkResult(startResults);
        response.put("stopPhase", stopResults);
        actionLog.info("Restart All finished (success=" + response.get("success") + ")");
        return response;
    }

    public List<String> getManagerLogs(Integer tail) {
        return actionLog.getLogs(tail);
    }

    private ProcessBuilder buildProcessBuilder(StackPilotProperties.ServiceDefinition def, String command) {
        ProcessBuilder pb;
        if (def.isDirectLaunch()) {
            pb = new ProcessBuilder(splitCommand(command));
        } else {
            pb = new ProcessBuilder("cmd.exe", "/c", command);
        }
        pb.directory(new File(def.getWorkingDir()));
        pb.redirectErrorStream(true);
        if (def.getEnvironment() != null && !def.getEnvironment().isEmpty()) {
            pb.environment().putAll(def.getEnvironment());
        }
        return pb;
    }

    private List<String> splitCommand(String command) {
        List<String> parts = new ArrayList<>();
        for (String token : command.trim().split("\\s+")) {
            if (!token.isBlank()) {
                parts.add(token);
            }
        }
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("Empty command");
        }
        return parts;
    }

    private void killAllExternalMatches(String name) {
        StackPilotProperties.ServiceDefinition def = properties.getServices().get(name);
        if (def == null || def.getProcessMatch() == null || def.getProcessMatch().isBlank()) {
            return;
        }
        if (def.getProcessName() == null || def.getProcessName().isBlank()) {
            log.warn("Cannot kill external matches for {} without process-name configured", name);
            return;
        }
        Set<Long> exclude = new HashSet<>();
        Process managed = processesMap.get(name);
        if (managed != null && managed.isAlive()) {
            exclude.add(managed.pid());
        }
        for (Long pid : processInspector.findMatchingProcesses(def.getProcessName(), def.getProcessMatch(), exclude)) {
            if (managed != null && managed.isAlive()
                    && processInspector.isSameProcessTree(managed.pid(), pid)) {
                continue;
            }
            actionLog.info("Killing orphaned/external " + def.getProcessName() + " for " + name + " (PID " + pid + ")");
            processInspector.killProcessTree(pid);
        }
    }

    private void invalidateExternalCache(String name) {
        externalScanCache.remove(name);
    }

    private void invalidateAllExternalCaches() {
        externalScanCache.clear();
    }

    private List<String> startOrder() {
        return new ArrayList<>(properties.getServices().keySet());
    }

    private List<String> stopOrder() {
        List<String> order = startOrder();
        Collections.reverse(order);
        return order;
    }

    private Map<String, Object> actionResult(String name, boolean success) {
        ServiceInfo info = getService(name);
        Map<String, Object> result = new HashMap<>();
        result.put("success", success);
        result.put("status", info != null ? info.getStatus() : null);
        result.put("errorMessage", info != null ? info.getErrorMessage() : null);
        return result;
    }

    private Map<String, Object> wrapBulkResult(Map<String, Object> results) {
        boolean allOk = results.values().stream()
                .filter(v -> v instanceof Map)
                .map(v -> (Map<?, ?>) v)
                .allMatch(m -> Boolean.TRUE.equals(m.get("success")));
        Map<String, Object> response = new HashMap<>();
        response.put("success", allOk);
        response.put("results", results);
        return response;
    }

    private void pauseBetweenServices() {
        pause(1500);
    }

    private void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public List<String> getLogs(String name, Integer tail) {
        LogStreamConsumer consumer = logConsumersMap.get(name);
        if (consumer == null) {
            ServiceInfo info = servicesMap.get(name);
            if (info != null && info.isExternal()) {
                return List.of(
                        "[StackPilot] Service is running externally (PID " + info.getPid() + "). Logs are not captured.",
                        "[StackPilot] Click Take Control on the service card, or Restart All, to kill the external process and relaunch under StackPilot.");
            }
            return Collections.singletonList("No active log consumer found for " + name);
        }

        Queue<String> allLogs = consumer.getInMemoryLogs();
        List<String> list = new ArrayList<>(allLogs);

        if (tail != null && tail > 0 && tail < list.size()) {
            return list.subList(list.size() - tail, list.size());
        }
        return list;
    }

    private void updateServiceStatus(String name) {
        ServiceInfo info = servicesMap.get(name);
        Process process = processesMap.get(name);

        if (process != null) {
            if (process.isAlive()) {
                info.setStatus(ServiceStatus.RUNNING);
                info.setExternal(false);
                info.setPid(process.pid());
                info.setErrorMessage(null);
            } else {
                int exitValue = process.exitValue();
                if (exitValue != 0 && info.getStatus() != ServiceStatus.STOPPED) {
                    info.setStatus(ServiceStatus.ERROR);
                    info.setErrorMessage("Process exited unexpectedly with code: " + exitValue);
                } else {
                    info.setStatus(ServiceStatus.STOPPED);
                    info.setErrorMessage(null);
                }
                info.setPid(null);
                info.setExternal(false);
                processesMap.remove(name);
                logConsumersMap.remove(name);
                refreshExternalStatus(name);
            }
            return;
        }

        refreshExternalStatus(name);
    }

    private void refreshExternalStatus(String name) {
        ServiceInfo info = servicesMap.get(name);
        Process managed = processesMap.get(name);
        if (managed != null && managed.isAlive()) {
            return;
        }

        OptionalLong externalPid = findExternalProcess(name);
        if (externalPid.isPresent()) {
            long pid = externalPid.getAsLong();
            info.setStatus(ServiceStatus.RUNNING_EXTERNAL);
            info.setExternal(true);
            info.setPid(pid);
            if (info.getErrorMessage() == null || !info.getErrorMessage().startsWith("Blocked:")) {
                info.setErrorMessage("Running externally (PID " + pid + "). Use Take Control or Restart All to kill and relaunch under StackPilot.");
            }
        } else if (info.getStatus() == ServiceStatus.RUNNING_EXTERNAL
                || (info.getStatus() == ServiceStatus.RUNNING && info.isExternal())) {
            info.setStatus(ServiceStatus.STOPPED);
            info.setExternal(false);
            info.setPid(null);
            info.setErrorMessage(null);
        } else if (info.getStatus() == ServiceStatus.RUNNING) {
            info.setStatus(ServiceStatus.STOPPED);
            info.setPid(null);
            info.setExternal(false);
        }
    }

    private OptionalLong findExternalProcess(String name) {
        Process managed = processesMap.get(name);
        if (managed != null && managed.isAlive()) {
            invalidateExternalCache(name);
            return OptionalLong.empty();
        }

        long now = System.currentTimeMillis();
        ExternalScanCache cached = externalScanCache.get(name);
        if (cached != null && (now - cached.scannedAtMs) < properties.getExternalScanIntervalMs()) {
            return cached.result;
        }

        OptionalLong result = scanExternalProcess(name);
        externalScanCache.put(name, new ExternalScanCache(now, result));
        return result;
    }

    private OptionalLong scanExternalProcess(String name) {
        StackPilotProperties.ServiceDefinition def = properties.getServices().get(name);
        if (def == null) {
            return OptionalLong.empty();
        }

        if (def.getPort() != null && processInspector.isPortInUse(def.getPort())) {
            OptionalLong pid = processInspector.findListeningPid(def.getPort());
            if (pid.isPresent()) {
                Process managed = processesMap.get(name);
                if (managed != null && managed.isAlive()
                        && processInspector.isSameProcessTree(managed.pid(), pid.getAsLong())) {
                    return OptionalLong.empty();
                }
                return pid;
            }
            return OptionalLong.of(-1L);
        }

        if (def.getProcessMatch() != null && !def.getProcessMatch().isBlank()) {
            if (def.getProcessName() == null || def.getProcessName().isBlank()) {
                log.warn("Service {} has process-match but no process-name; skipping external scan", name);
                return OptionalLong.empty();
            }
            Set<Long> exclude = new HashSet<>();
            Process managed = processesMap.get(name);
            if (managed != null && managed.isAlive()) {
                exclude.add(managed.pid());
            }
            List<Long> pids = processInspector.findMatchingProcesses(
                    def.getProcessName(), def.getProcessMatch(), exclude);
            if (pids.isEmpty()) {
                return OptionalLong.empty();
            }
            if (pids.size() > 1) {
                actionLog.warn("Multiple external " + name + " processes: " + pids);
            }
            return OptionalLong.of(pids.getFirst());
        }

        return OptionalLong.empty();
    }

    private boolean hasExternalPresence(String name) {
        return findExternalProcess(name).isPresent();
    }

    private String buildExternalBlockMessage(String name, long pid) {
        StackPilotProperties.ServiceDefinition def = properties.getServices().get(name);
        String pidLabel = pid > 0 ? String.valueOf(pid) : "unknown";
        if (def != null && def.getPort() != null) {
            return "Blocked: " + name + " already running on port " + def.getPort()
                    + " (PID " + pidLabel + "). Use Stop to terminate the external process first.";
        }
        return "Blocked: " + name + " already running externally (PID " + pidLabel
                + "). Use Stop to terminate the external process first.";
    }

    private void killProcessTree(Process p) {
        if (p == null) {
            return;
        }
        long pid = p.pid();

        try {
            p.toHandle().descendants().forEach(h -> {
                log.debug("Destroying descendant process PID: {}", h.pid());
                h.destroyForcibly();
            });
            p.destroyForcibly();
        } catch (Exception e) {
            log.warn("Java standard destroy failed for PID {}: {}", pid, e.getMessage());
        }

        processInspector.killProcessTree(pid);
    }

    @PreDestroy
    public void cleanup() {
        log.info("Shutdown detected. Cleaning up managed processes only...");
        for (String name : new ArrayList<>(processesMap.keySet())) {
            try {
                stopService(name);
            } catch (Exception e) {
                log.error("Error stopping service {} during shutdown", name, e);
            }
        }
        executorService.shutdownNow();
    }
}