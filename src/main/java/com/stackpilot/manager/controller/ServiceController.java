package com.stackpilot.manager.controller;

import com.stackpilot.manager.model.ServiceInfo;
import com.stackpilot.manager.service.ServiceManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/services")
@CrossOrigin(origins = "*") // Allow cross-origin requests for dashboard
public class ServiceController {

    private final ServiceManager serviceManager;

    @Autowired
    public ServiceController(ServiceManager serviceManager) {
        this.serviceManager = serviceManager;
    }

    @GetMapping
    public ResponseEntity<List<ServiceInfo>> getAllServices() {
        return ResponseEntity.ok(serviceManager.getServices());
    }

    @GetMapping("/{name}")
    public ResponseEntity<ServiceInfo> getService(@PathVariable String name) {
        ServiceInfo info = serviceManager.getService(name);
        if (info == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(info);
    }

    @PostMapping("/{name}/start")
    public ResponseEntity<Map<String, Object>> startService(@PathVariable String name) {
        return actionResponse(name, serviceManager.startService(name));
    }

    @PostMapping("/{name}/stop")
    public ResponseEntity<Map<String, Object>> stopService(@PathVariable String name) {
        return actionResponse(name, serviceManager.stopService(name));
    }

    @PostMapping("/{name}/restart")
    public ResponseEntity<Map<String, Object>> restartService(@PathVariable String name) {
        return actionResponse(name, serviceManager.restartService(name));
    }

    /** Alias for restart — kills external/managed process and starts under StackPilot. */
    @PostMapping("/{name}/takeover")
    public ResponseEntity<Map<String, Object>> takeoverService(@PathVariable String name) {
        return actionResponse(name, serviceManager.takeoverService(name));
    }

    private ResponseEntity<Map<String, Object>> actionResponse(String name, boolean success) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", success);
        response.put("serviceName", name);
        ServiceInfo info = serviceManager.getService(name);
        response.put("status", info.getStatus());
        response.put("errorMessage", info.getErrorMessage());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{name}/logs")
    public ResponseEntity<List<String>> getLogs(
            @PathVariable String name,
            @RequestParam(value = "tail", required = false) Integer tail) {
        List<String> logs = serviceManager.getLogs(name, tail);
        return ResponseEntity.ok(logs);
    }
}
