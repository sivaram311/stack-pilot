package com.stackpilot.manager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.stackpilot.manager.config.StackPilotProperties;

@SpringBootApplication
@EnableConfigurationProperties(StackPilotProperties.class)
@EnableScheduling
public class StackPilotApplication {
    public static void main(String[] args) {
        SpringApplication.run(StackPilotApplication.class, args);
    }
}
