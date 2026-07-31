package com.stackpilot.manager.controller;

import com.stackpilot.manager.service.AiDlcStatusService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/aidlc")
@CrossOrigin(origins = "*")
public class AiDlcStatusController {

    private final AiDlcStatusService aiDlcStatusService;

    public AiDlcStatusController(AiDlcStatusService aiDlcStatusService) {
        this.aiDlcStatusService = aiDlcStatusService;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return aiDlcStatusService.summary();
    }
}
