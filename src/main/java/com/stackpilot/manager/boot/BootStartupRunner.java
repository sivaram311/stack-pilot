package com.stackpilot.manager.boot;

import com.stackpilot.manager.config.StackPilotProperties;
import com.stackpilot.manager.service.ManagerActionLog;
import com.stackpilot.manager.service.NginxManager;
import com.stackpilot.manager.service.ServiceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Runs optional nginx + grok_dev service startup after Stack Pilot boots
 * (e.g. following a machine restart when registered via setup-boot-tasks.ps1).
 */
@Component
public class BootStartupRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(BootStartupRunner.class);

    private final StackPilotProperties properties;
    private final ServiceManager serviceManager;
    private final NginxManager nginxManager;
    private final ManagerActionLog actionLog;

    public BootStartupRunner(
            StackPilotProperties properties,
            ServiceManager serviceManager,
            NginxManager nginxManager,
            ManagerActionLog actionLog) {
        this.properties = properties;
        this.serviceManager = serviceManager;
        this.nginxManager = nginxManager;
        this.actionLog = actionLog;
    }

    @Override
    public void run(ApplicationArguments args) {
        StackPilotProperties.BootSettings boot = properties.getBoot();
        if (!boot.isAutoStartNginx() && !boot.isAutoStartServices()) {
            return;
        }

        Thread bootThread = new Thread(this::runBootSequence, "stackpilot-boot-startup");
        bootThread.setDaemon(true);
        bootThread.start();
    }

    private void runBootSequence() {
        StackPilotProperties.BootSettings boot = properties.getBoot();
        long delayMs = Math.max(0, boot.getStartupDelayMs());

        if (delayMs > 0) {
            log.info("Boot startup: waiting {} ms before auto-start actions", delayMs);
            actionLog.info("Boot startup: waiting " + (delayMs / 1000) + "s before auto-start");
            pause(delayMs);
        }

        if (boot.isAutoStartNginx()) {
            log.info("Boot startup: ensuring nginx is running");
            actionLog.info("Boot startup: auto-start nginx");
            Map<String, Object> result = nginxManager.start();
            if (Boolean.TRUE.equals(result.get("success"))) {
                actionLog.info("Boot startup: nginx OK — " + result.get("message"));
            } else {
                actionLog.warn("Boot startup: nginx failed — " + result.get("message"));
            }
        }

        if (boot.isAutoStartServices()) {
            log.info("Boot startup: starting all grok_dev services");
            actionLog.info("Boot startup: auto-start all services");
            Map<String, Object> result = serviceManager.startAllServices();
            if (Boolean.TRUE.equals(result.get("success"))) {
                actionLog.info("Boot startup: all services started");
            } else {
                actionLog.warn("Boot startup: some services failed to start");
            }
        }
    }

    private void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Boot startup delay interrupted");
        }
    }
}
