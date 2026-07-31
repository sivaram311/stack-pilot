package com.stackpilot.manager.controller;

import com.stackpilot.manager.service.PlatformSummaryService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/platform")
@CrossOrigin(origins = "*")
public class PlatformController {

    private final PlatformSummaryService platformSummaryService;

    public PlatformController(PlatformSummaryService platformSummaryService) {
        this.platformSummaryService = platformSummaryService;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return platformSummaryService.summary();
    }
}
