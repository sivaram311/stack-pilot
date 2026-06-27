package com.stackpilot.manager.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ServiceInfo {
    private String name;
    private String workingDir;
    private String command;
    private ServiceStatus status = ServiceStatus.STOPPED;
    private Long pid = null;
    private String errorMessage = null;
    private Integer port = null;
    /** True when the service is running but was not started by StackPilot. */
    private boolean external = false;
}
