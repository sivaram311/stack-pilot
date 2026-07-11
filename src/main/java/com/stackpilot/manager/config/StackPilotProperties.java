package com.stackpilot.manager.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@ConfigurationProperties(prefix = "stackpilot")
public class StackPilotProperties {

    private String logsDir = "logs";
    private int maxLogLines = 500;
    /** Minimum milliseconds between external process scans per service (dashboard polling). */
    private long externalScanIntervalMs = 5000;
    private AuthSettings auth = new AuthSettings();
    private NginxSettings nginx = new NginxSettings();
    private RdpSettings rdp = new RdpSettings();
    private HostSettings host = new HostSettings();
    private BootSettings boot = new BootSettings();
    private Map<String, ServiceDefinition> services = new LinkedHashMap<>();

    @Data
    public static class AuthSettings {
        /** Require API key for non-localhost requests when api-key is set. */
        private boolean enabled = true;
        private String apiKey = "";
        /** Allow 127.0.0.1 / ::1 without API key (nginx proxy on same host). */
        private boolean allowLocalhostWithoutKey = true;
        /** Skip API key when X-Real-IP / X-Forwarded-For present (behind nginx basic auth). */
        private boolean trustProxyHeaders = true;
    }

    @Data
    public static class BootSettings {
        /** After Stack Pilot starts, optionally start all profile-managed services. */
        private boolean autoStartServices = true;
        /** Fallback: start nginx from Stack Pilot if the boot task did not (already-running is a no-op). */
        private boolean autoStartNginx = true;
        /** Ensure fResetBroken=1 on boot (idempotent). */
        private boolean autoApplyRdpMitigations = true;
        /** Wait after JVM start before boot actions (network, Postgres, MT5). */
        private long startupDelayMs = 45000;
    }

    @Data
    public static class RdpSettings {
        private boolean enabled = true;
        private String scriptsHome = "E:/Source/stack-pilot";
        private String confirmPhraseRecover = "RECOVER RDP";
        private String confirmPhraseApplyMitigations = "APPLY RDP FIX";
        private MonitorSettings monitor = new MonitorSettings();
        private WebhookSettings webhook = new WebhookSettings();

        @Data
        public static class MonitorSettings {
            private boolean enabled = true;
            private long pollIntervalMs = 60000;
            private int alertAfterCrashesInHours = 2;
        }

        @Data
        public static class WebhookSettings {
            private boolean enabled = false;
            /** Discord/Slack/generic POST URL. */
            private String url = "";
            /** discord | generic */
            private String format = "discord";
            private int cooldownMinutes = 30;
            private String dashboardUrl = "https://control.delena.buzz/";
        }
    }

    @Data
    public static class NginxSettings {
        private String home = "C:/nginx-1.30.3";
        private int port = 80;
        private String errorLog = "logs/error.log";
        private String accessLog = "logs/access.log";
        private List<HealthCheck> healthChecks = new ArrayList<>();

        @Data
        public static class HealthCheck {
            private String name;
            private String url;
            private String hostHeader;
        }
    }

    @Data
    public static class HostSettings {
        private boolean enabled = true;
        private int shutdownDelaySeconds = 60;
        private String confirmPhraseRestart = "RESTART SERVER";
        private String confirmPhraseShutdown = "SHUTDOWN SERVER";
    }

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