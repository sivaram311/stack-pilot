package com.stackpilot.manager.controller;

import com.stackpilot.manager.service.NginxManager;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/infrastructure/nginx")
@CrossOrigin(origins = "*")
public class NginxController {

    private final NginxManager nginxManager;

    public NginxController(NginxManager nginxManager) {
        this.nginxManager = nginxManager;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return nginxManager.getStatus();
    }

    @PostMapping("/start")
    public Map<String, Object> start() {
        return nginxManager.start();
    }

    @PostMapping("/stop")
    public Map<String, Object> stop() {
        return nginxManager.stop();
    }

    @PostMapping("/reload")
    public Map<String, Object> reload() {
        return nginxManager.reload();
    }

    @GetMapping("/logs/error")
    public List<String> errorLogs(@RequestParam(value = "tail", required = false) Integer tail) {
        return nginxManager.getErrorLogTail(tail);
    }

    @GetMapping("/logs/access")
    public List<String> accessLogs(@RequestParam(value = "tail", required = false) Integer tail) {
        return nginxManager.getAccessLogTail(tail);
    }
}
