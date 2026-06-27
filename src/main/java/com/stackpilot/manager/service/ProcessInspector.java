package com.stackpilot.manager.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ProcessInspector {
    private static final Logger log = LoggerFactory.getLogger(ProcessInspector.class);
    private static final long COMMAND_TIMEOUT_SECONDS = 5;

    public boolean isPortInUse(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public OptionalLong findListeningPid(int port) {
        if (!isPortInUse(port)) {
            return OptionalLong.empty();
        }
        Pattern pattern = Pattern.compile(":" + port + "\\s+.*LISTENING\\s+(\\d+)\\s*$", Pattern.CASE_INSENSITIVE);
        try {
            Process proc = new ProcessBuilder("netstat", "-ano", "-p", "tcp").start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher matcher = pattern.matcher(line.trim());
                    if (matcher.find()) {
                        waitForProcess(proc);
                        return OptionalLong.of(Long.parseLong(matcher.group(1)));
                    }
                }
            }
            waitForProcess(proc);
        } catch (Exception e) {
            log.debug("Failed to resolve PID for port {}: {}", port, e.getMessage());
        }
        return OptionalLong.empty();
    }

    /**
     * Find processes by executable name + command-line fragment.
     * Requires processName (e.g. python.exe) so inspector PowerShell processes never self-match.
     */
    public List<Long> findMatchingProcesses(String processName, String commandLineFragment, Set<Long> excludePids) {
        List<Long> pids = new ArrayList<>();
        if (commandLineFragment == null || commandLineFragment.isBlank()) {
            return pids;
        }
        if (processName == null || processName.isBlank()) {
            log.warn("process-name is required for command-line matching; skipping scan");
            return pids;
        }

        String escapedCmd = commandLineFragment.replace("'", "''");
        String escapedName = processName.replace("'", "''");
        String ps = "Get-CimInstance Win32_Process | Where-Object { $_.Name -ieq '" + escapedName
                + "' -and $_.CommandLine -like '*" + escapedCmd + "*' } | Select-Object -ExpandProperty ProcessId";

        Process proc = null;
        try {
            proc = new ProcessBuilder("powershell", "-NoProfile", "-Command", ps).start();
            long inspectorPid = proc.pid();
            Set<Long> exclude = new HashSet<>(excludePids != null ? excludePids : Set.of());
            exclude.add(inspectorPid);

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    long pid = Long.parseLong(line.trim());
                    if (!exclude.contains(pid)) {
                        pids.add(pid);
                    }
                }
            }
            waitForProcess(proc);
        } catch (Exception e) {
            log.debug("Failed to list {} processes for '{}': {}", processName, commandLineFragment, e.getMessage());
            if (proc != null) {
                proc.destroyForcibly();
            }
        }
        return pids;
    }

    public boolean isSameProcessTree(long managedPid, long candidatePid) {
        if (managedPid == candidatePid) {
            return true;
        }
        return isDescendantOf(candidatePid, managedPid) || isDescendantOf(managedPid, candidatePid);
    }

    private boolean isDescendantOf(long childPid, long ancestorPid) {
        long current = childPid;
        for (int depth = 0; depth < 32; depth++) {
            OptionalLong parent = findParentPid(current);
            if (parent.isEmpty()) {
                return false;
            }
            if (parent.getAsLong() == ancestorPid) {
                return true;
            }
            current = parent.getAsLong();
        }
        return false;
    }

    private OptionalLong findParentPid(long pid) {
        String command = "Get-CimInstance Win32_Process -Filter \"ProcessId=" + pid + "\" | Select-Object -ExpandProperty ParentProcessId";
        try {
            Process proc = new ProcessBuilder("powershell", "-NoProfile", "-Command", command).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                waitForProcess(proc);
                if (line != null && !line.isBlank()) {
                    return OptionalLong.of(Long.parseLong(line.trim()));
                }
            }
        } catch (Exception e) {
            log.debug("Failed to resolve parent for PID {}: {}", pid, e.getMessage());
        }
        return OptionalLong.empty();
    }

    public boolean isProcessAlive(long pid) {
        try {
            Process proc = new ProcessBuilder("tasklist", "/FI", "PID eq " + pid, "/NH").start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                waitForProcess(proc);
                return line != null && !line.isBlank() && line.contains(String.valueOf(pid));
            }
        } catch (Exception e) {
            return false;
        }
    }

    public void killProcessTree(long pid) {
        if (pid <= 0) {
            return;
        }
        try {
            new ProcessBuilder("taskkill", "/F", "/T", "/PID", String.valueOf(pid)).start().waitFor();
            log.info("Terminated process tree for PID {}", pid);
        } catch (Exception e) {
            log.error("Failed to taskkill PID {}", pid, e);
        }
    }

    private void waitForProcess(Process proc) {
        try {
            if (!proc.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                log.warn("Timed out waiting for process command");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
        }
    }
}