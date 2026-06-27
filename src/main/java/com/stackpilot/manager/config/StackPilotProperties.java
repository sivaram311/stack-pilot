package com.stackpilot.manager.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@ConfigurationProperties(prefix = "stackpilot")
public class StackPilotProperties {

    private String logsDir = "logs";
    private int maxLogLines = 500;
    /** Minimum milliseconds between external process scans per service (dashboard polling). */
    private long externalScanIntervalMs = 5000;
    private Map<String, ServiceDefinition> services = new LinkedHashMap<>();

    @Data
    public static class ServiceDefinition {
        private String workingDir;
        private String command;
        private String logFile;
        private Integer port;
        /** Command-line substring for external detection (requires process-name). */
        private String processMatch;
        /** Executable file name filter, e.g. python.exe — prevents scanner self-match. */
        private String processName;
        private boolean directLaunch = false;
        private Map<String, String> environment = new LinkedHashMap<>();
    }
}