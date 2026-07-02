package com.stackpilot.manager.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stackpilot.manager.config.StackPilotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class WebhookNotifier {
    private static final Logger log = LoggerFactory.getLogger(WebhookNotifier.class);

    private final StackPilotProperties properties;
    private final ManagerActionLog actionLog;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private volatile Instant lastAlertAt = Instant.EPOCH;
    private volatile String lastCrashFingerprint = "";

    public WebhookNotifier(StackPilotProperties properties, ManagerActionLog actionLog) {
        this.properties = properties;
        this.actionLog = actionLog;
    }

    public boolean notifyRdpIssue(String alertType, Map<String, Object> rdpStatus) {
        StackPilotProperties.RdpSettings.WebhookSettings webhook = properties.getRdp().getWebhook();
        if (!webhook.isEnabled() || webhook.getUrl() == null || webhook.getUrl().isBlank()) {
            return false;
        }

        String fingerprint = buildFingerprint(alertType, rdpStatus);
        long cooldownMinutes = Math.max(1, webhook.getCooldownMinutes());
        Instant now = Instant.now();
        if (fingerprint.equals(lastCrashFingerprint)
                && Duration.between(lastAlertAt, now).toMinutes() < cooldownMinutes) {
            return false;
        }

        try {
            String body = buildPayload(alertType, rdpStatus);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhook.getUrl().trim()))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                lastAlertAt = now;
                lastCrashFingerprint = fingerprint;
                actionLog.warn("RDP webhook sent: " + alertType);
                log.info("RDP webhook delivered ({})", alertType);
                return true;
            }
            log.warn("RDP webhook failed HTTP {}: {}", response.statusCode(), response.body());
            actionLog.error("RDP webhook HTTP " + response.statusCode());
        } catch (Exception e) {
            log.error("RDP webhook delivery failed", e);
            actionLog.error("RDP webhook failed: " + e.getMessage());
        }
        return false;
    }

    private String buildFingerprint(String alertType, Map<String, Object> rdpStatus) {
        if ("rdp.crash".equals(alertType)) {
            Object crash = rdpStatus.get("lastRdpcoretsCrash");
            if (crash instanceof Map<?, ?> crashMap) {
                Object time = crashMap.get("timeCreated");
                if (time != null) {
                    return alertType + ":" + time;
                }
            }
        }
        if ("rdp.termservice_down".equals(alertType)) {
            Object ts = rdpStatus.get("termService");
            if (ts instanceof Map<?, ?> map) {
                return alertType + ":" + map.get("status");
            }
        }
        Object warnings = rdpStatus.get("warnings");
        return alertType + ":" + String.valueOf(warnings);
    }

    private String buildPayload(String alertType, Map<String, Object> rdpStatus) throws Exception {
        String hostname = System.getenv().getOrDefault("COMPUTERNAME", "unknown-host");
        String dashboardUrl = properties.getRdp().getWebhook().getDashboardUrl();
        if (dashboardUrl == null || dashboardUrl.isBlank()) {
            dashboardUrl = "http://control.delena.buzz/";
        }

        String title = switch (alertType) {
            case "rdp.crash" -> "RDP crash detected (rdpcorets.dll)";
            case "rdp.termservice_down" -> "Remote Desktop Services is not running";
            case "rdp.degraded" -> "RDP health degraded";
            default -> "RDP alert: " + alertType;
        };

        StringBuilder details = new StringBuilder();
        Object warnings = rdpStatus.get("warnings");
        if (warnings instanceof List<?> list && !list.isEmpty()) {
            details.append(String.join("; ", list.stream().map(String::valueOf).toList()));
        }
        Object crash = rdpStatus.get("lastRdpcoretsCrash");
        if (crash instanceof Map<?, ?> crashMap && crashMap.get("message") != null) {
            if (!details.isEmpty()) details.append(" | ");
            details.append(crashMap.get("message"));
        }

        String format = properties.getRdp().getWebhook().getFormat();
        if ("discord".equalsIgnoreCase(format)) {
            Map<String, Object> embed = new LinkedHashMap<>();
            embed.put("title", title);
            embed.put("description", details.isEmpty() ? "Check Stack Pilot dashboard." : details.toString());
            embed.put("color", 16_750_592);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("content", "**" + hostname + "** — " + title);
            payload.put("embeds", List.of(embed));
            return objectMapper.writeValueAsString(payload);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", alertType);
        payload.put("host", hostname);
        payload.put("title", title);
        payload.put("message", details.toString());
        payload.put("dashboardUrl", dashboardUrl);
        payload.put("rdpStatus", rdpStatus);
        payload.put("timestamp", Instant.now().toString());
        return objectMapper.writeValueAsString(payload);
    }
}
