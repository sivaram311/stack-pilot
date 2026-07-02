package com.stackpilot.manager.controller;

import com.stackpilot.manager.service.RdpHealthService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/infrastructure/rdp")
@CrossOrigin(origins = "*")
public class RdpHealthController {

    private final RdpHealthService rdpHealthService;

    public RdpHealthController(RdpHealthService rdpHealthService) {
        this.rdpHealthService = rdpHealthService;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return rdpHealthService.getStatus();
    }

    @PostMapping("/recover")
    public Map<String, Object> recover(@RequestBody RdpActionRequest request) {
        String phrase = request != null ? request.confirmPhrase() : null;
        return rdpHealthService.recover(phrase);
    }

    @PostMapping("/apply-mitigations")
    public Map<String, Object> applyMitigations(@RequestBody RdpActionRequest request) {
        String phrase = request != null ? request.confirmPhrase() : null;
        return rdpHealthService.applyMitigations(phrase);
    }

    public record RdpActionRequest(String confirmPhrase) {}
}
