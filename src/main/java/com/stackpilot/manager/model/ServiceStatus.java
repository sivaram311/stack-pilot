package com.stackpilot.manager.model;

public enum ServiceStatus {
    STOPPED,
    STARTING,
    RUNNING,
    /** Running outside StackPilot (detected by port or process match). */
    RUNNING_EXTERNAL,
    ERROR
}
