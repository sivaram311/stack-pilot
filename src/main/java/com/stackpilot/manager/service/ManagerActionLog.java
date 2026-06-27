package com.stackpilot.manager.service;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

@Component
public class ManagerActionLog {
    private static final int MAX_LINES = 500;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Queue<String> lines = new ConcurrentLinkedQueue<>();

    public void info(String message) {
        add("INFO", message);
    }

    public void warn(String message) {
        add("WARN", message);
    }

    public void error(String message) {
        add("ERROR", message);
    }

    private void add(String level, String message) {
        lines.add("[" + LocalDateTime.now().format(TIME) + "] [" + level + "] " + message);
        while (lines.size() > MAX_LINES) {
            lines.poll();
        }
    }

    public List<String> getLogs(Integer tail) {
        List<String> list = new ArrayList<>(lines);
        if (tail != null && tail > 0 && tail < list.size()) {
            return list.subList(list.size() - tail, list.size());
        }
        return list;
    }
}