package com.stackpilot.manager.controller;

import com.stackpilot.manager.config.StackPilotProperties;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
public class AuthController {

    private final StackPilotProperties properties;

    public AuthController(StackPilotProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        StackPilotProperties.AuthSettings auth = properties.getAuth();
        Map<String, Object> status = new LinkedHashMap<>();
        boolean keyConfigured = auth.getApiKey() != null && !auth.getApiKey().isBlank();
        status.put("enabled", auth.isEnabled() && keyConfigured);
        status.put("allowLocalhostWithoutKey", auth.isAllowLocalhostWithoutKey());
        status.put("headerName", "X-StackPilot-Api-Key");
        return status;
    }
}
