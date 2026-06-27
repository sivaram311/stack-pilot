package com.stackpilot.manager.controller;

import com.stackpilot.manager.service.ServiceManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/services/bulk")
@CrossOrigin(origins = "*")
public class ServiceBulkController {

    private final ServiceManager serviceManager;

    public ServiceBulkController(ServiceManager serviceManager) {
        this.serviceManager = serviceManager;
    }

    @PostMapping("/stop-all")
    public ResponseEntity<Map<String, Object>> stopAllServices() {
        return ResponseEntity.ok(serviceManager.stopAllServices());
    }

    @PostMapping("/start-all")
    public ResponseEntity<Map<String, Object>> startAllServices() {
        return ResponseEntity.ok(serviceManager.startAllServices());
    }

    @PostMapping("/restart-all")
    public ResponseEntity<Map<String, Object>> restartAllServices() {
        return ResponseEntity.ok(serviceManager.restartAllServices());
    }
}