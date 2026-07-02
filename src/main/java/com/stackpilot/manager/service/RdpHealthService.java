package com.stackpilot.manager.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stackpilot.manager.config.StackPilotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class RdpHealthService {
    private static final Logger log = LoggerFactory.getLogger(RdpHealthService.class);
    private static final long COMMAND_TIMEOUT_SECONDS = 60;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StackPilotProperties properties;
    private final ManagerActionLog actionLog;

    public RdpHealthService(StackPilotProperties properties, ManagerActionLog actionLog) {
        this.properties = properties;
        this.actionLog = actionLog;
    }

    public Map<String, Object> getStatus() {
        if (!properties.getRdp().isEnabled()) {
            return Map.of("enabled", false, "message", "RDP health monitoring is disabled");
        }

        try {
            Map<String, Object> status = runScriptJson("rdp-status.ps1", List.of());
            status.put("enabled", true);
            status.put("confirmPhraseRecover", properties.getRdp().getConfirmPhraseRecover());
            status.put("confirmPhraseApplyMitigations", properties.getRdp().getConfirmPhraseApplyMitigations());
            return status;
        } catch (Exception e) {
            log.error("Failed to read RDP status", e);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("enabled", true);
            error.put("healthy", false);
            error.put("warnings", List.of("Status check failed: " + e.getMessage()));
            error.put("error", e.getMessage());
            return error;
        }
    }

    public Map<String, Object> applyMitigations(String confirmPhrase) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!properties.getRdp().isEnabled()) {
            result.put("success", false);
            result.put("message", "RDP health controls are disabled");
            return result;
        }

        if (!matchesPhrase(confirmPhrase, properties.getRdp().getConfirmPhraseApplyMitigations())) {
            result.put("success", false);
            result.put("message", "Confirmation phrase does not match");
            actionLog.warn("RDP apply-mitigations blocked: invalid confirmation phrase");
            return result;
        }

        try {
            Map<String, Object> scriptResult = runScriptJson("rdp-apply-mitigations.ps1", List.of());
            result.putAll(scriptResult);
            if (Boolean.TRUE.equals(scriptResult.get("success"))) {
                actionLog.info("RDP mitigations applied: " + scriptResult.get("message"));
            } else {
                actionLog.error("RDP mitigations failed: " + scriptResult.get("message"));
            }
        } catch (Exception e) {
            log.error("RDP apply-mitigations failed", e);
            result.put("success", false);
            result.put("message", e.getMessage());
            actionLog.error("RDP apply-mitigations failed: " + e.getMessage());
        }
        return result;
    }

    public Map<String, Object> recover(String confirmPhrase) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!properties.getRdp().isEnabled()) {
            result.put("success", false);
            result.put("message", "RDP health controls are disabled");
            return result;
        }

        if (!matchesPhrase(confirmPhrase, properties.getRdp().getConfirmPhraseRecover())) {
            result.put("success", false);
            result.put("message", "Confirmation phrase does not match");
            actionLog.warn("RDP recover blocked: invalid confirmation phrase");
            return result;
        }

        try {
            Map<String, Object> scriptResult = runScriptJson("rdp-recover-session.ps1", List.of());
            result.putAll(scriptResult);
            if (Boolean.TRUE.equals(scriptResult.get("success"))) {
                actionLog.warn("RDP session recover: " + scriptResult.get("message"));
            } else {
                actionLog.error("RDP recover failed: " + scriptResult.get("message"));
            }
        } catch (Exception e) {
            log.error("RDP recover failed", e);
            result.put("success", false);
            result.put("message", e.getMessage());
            actionLog.error("RDP recover failed: " + e.getMessage());
        }
        return result;
    }

    public Map<String, Object> applyMitigationsSilent() {
        try {
            return runScriptJson("rdp-apply-mitigations.ps1", List.of());
        } catch (Exception e) {
            log.warn("Silent RDP mitigation apply failed: {}", e.getMessage());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("message", e.getMessage());
            return result;
        }
    }

    private boolean matchesPhrase(String actual, String expected) {
        return actual != null && actual.trim().equals(expected);
    }

    private Map<String, Object> runScriptJson(String scriptName, List<String> extraArgs) throws Exception {
        Path scriptPath = resolveScript(scriptName);
        List<String> command = new java.util.ArrayList<>();
        command.add("powershell");
        command.add("-NoProfile");
        command.add("-ExecutionPolicy");
        command.add("Bypass");
        command.add("-File");
        command.add(scriptPath.toString());
        command.addAll(extraArgs);

        Process proc = new ProcessBuilder(command).redirectErrorStream(true).start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
        }

        if (!proc.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            throw new InterruptedException("Script timed out: " + scriptName);
        }

        if (proc.exitValue() != 0 && output.isEmpty()) {
            throw new IllegalStateException("Script failed with exit code " + proc.exitValue() + ": " + scriptName);
        }

        String json = output.toString().trim();
        if (json.isEmpty()) {
            throw new IllegalStateException("Script produced no output: " + scriptName);
        }

        Map<String, Object> parsed = MAPPER.readValue(json, new TypeReference<>() {});
        if (proc.exitValue() != 0 && !Boolean.TRUE.equals(parsed.get("success"))) {
            throw new IllegalStateException(
                    String.valueOf(parsed.getOrDefault("message", "Script exit " + proc.exitValue())));
        }
        return parsed;
    }

    private Path resolveScript(String scriptName) {
        String home = properties.getRdp().getScriptsHome();
        if (home == null || home.isBlank()) {
            home = "E:/Source/stack-pilot";
        }
        Path path = Paths.get(home, "scripts", scriptName);
        if (!path.toFile().isFile()) {
            throw new IllegalStateException("Script not found: " + path);
        }
        return path;
    }
}
