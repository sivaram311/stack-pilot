package com.stackpilot.manager.controller;

import com.stackpilot.manager.service.ServiceManager;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/manager")
@CrossOrigin(origins = "*")
public class ManagerLogController {

    private final ServiceManager serviceManager;

    public ManagerLogController(ServiceManager serviceManager) {
        this.serviceManager = serviceManager;
    }

    @GetMapping("/logs")
    public List<String> getManagerLogs(@RequestParam(value = "tail", required = false) Integer tail) {
        return serviceManager.getManagerLogs(tail);
    }
}