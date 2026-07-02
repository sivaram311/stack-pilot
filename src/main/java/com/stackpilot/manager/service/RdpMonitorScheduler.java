package com.stackpilot.manager.service;

import com.stackpilot.manager.config.StackPilotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

@Component
public class RdpMonitorScheduler {
    private static final Logger log = LoggerFactory.getLogger(RdpMonitorScheduler.class);

    private final StackPilotProperties properties;
    private final RdpHealthService rdpHealthService;
    private final WebhookNotifier webhookNotifier;

    private volatile String lastSeenCrashTime = "";

    public RdpMonitorScheduler(
            StackPilotProperties properties,
            RdpHealthService rdpHealthService,
            WebhookNotifier webhookNotifier) {
        this.properties = properties;
        this.rdpHealthService = rdpHealthService;
        this.webhookNotifier = webhookNotifier;
    }

    @Scheduled(fixedDelayString = "${stackpilot.rdp.monitor.poll-interval-ms:60000}")
    public void pollRdpHealth() {
        if (!properties.getRdp().isEnabled() || !properties.getRdp().getMonitor().isEnabled()) {
            return;
        }

        try {
            Map<String, Object> status = rdpHealthService.getStatus();
            evaluateAndNotify(status);
        } catch (Exception e) {
            log.debug("RDP monitor poll failed: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void evaluateAndNotify(Map<String, Object> status) {
        Map<String, Object> termService = (Map<String, Object>) status.get("termService");
        String termStatus = termService != null ? String.valueOf(termService.get("status")) : "Unknown";

        if (!"Running".equalsIgnoreCase(termStatus)) {
            webhookNotifier.notifyRdpIssue("rdp.termservice_down", status);
            return;
        }

        Map<String, Object> lastCrash = (Map<String, Object>) status.get("lastRdpcoretsCrash");
        if (lastCrash != null && lastCrash.get("timeCreated") != null) {
            String crashTime = String.valueOf(lastCrash.get("timeCreated"));
            if (!crashTime.equals(lastSeenCrashTime) && isRecentCrash(crashTime)) {
                lastSeenCrashTime = crashTime;
                webhookNotifier.notifyRdpIssue("rdp.crash", status);
                return;
            }
        }

        Object healthy = status.get("healthy");
        if (Boolean.FALSE.equals(healthy)) {
            webhookNotifier.notifyRdpIssue("rdp.degraded", status);
        }
    }

    private boolean isRecentCrash(String crashTimeIso) {
        try {
            Instant crash = OffsetDateTime.parse(crashTimeIso).toInstant();
            int hours = Math.max(1, properties.getRdp().getMonitor().getAlertAfterCrashesInHours());
            return crash.isAfter(Instant.now().minusSeconds(hours * 3600L));
        } catch (DateTimeParseException e) {
            return true;
        }
    }
}
