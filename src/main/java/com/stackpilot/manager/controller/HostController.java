package com.stackpilot.manager.controller;

import com.stackpilot.manager.service.HostControlService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/host")
@CrossOrigin(origins = "*")
public class HostController {

    private final HostControlService hostControlService;

    public HostController(HostControlService hostControlService) {
        this.hostControlService = hostControlService;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return hostControlService.getStatus();
    }

    @PostMapping("/restart")
    public Map<String, Object> restart(@RequestBody HostActionRequest request) {
        String phrase = request != null ? request.confirmPhrase() : null;
        return hostControlService.scheduleRestart(phrase);
    }

    @PostMapping("/shutdown")
    public Map<String, Object> shutdown(@RequestBody HostActionRequest request) {
        String phrase = request != null ? request.confirmPhrase() : null;
        return hostControlService.scheduleShutdown(phrase);
    }

    @PostMapping("/cancel")
    public Map<String, Object> cancel() {
        return hostControlService.cancelPending();
    }

    public record HostActionRequest(String confirmPhrase) {}
}
