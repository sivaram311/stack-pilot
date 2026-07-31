package com.stackpilot.manager.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class PlatformSummaryService {

    public static final String VERSION_LABEL = "0.2.0-a";

    private final Environment environment;

    @Value("${server.port:8091}")
    private int serverPort;

    public PlatformSummaryService(Environment environment) {
        this.environment = environment;
    }

    public Map<String, Object> summary() {
        String profile = primaryProfile();
        Path cwd = Path.of("").toAbsolutePath().normalize();
        String driveLetter = cwd.getRoot() != null
                ? cwd.getRoot().toString().replace("\\", "").replace(":", "").toUpperCase(Locale.ROOT)
                : "?";

        EnvRole envRole = resolveEnv(profile, driveLetter);

        Map<String, Object> env = new LinkedHashMap<>();
        env.put("drive", envRole.drive());
        env.put("label", envRole.label());
        env.put("role", envRole.role());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("app", "stack-pilot");
        out.put("versionLabel", VERSION_LABEL);
        out.put("profile", profile);
        out.put("serverPort", serverPort);
        out.put("workingDirectory", cwd.toString());
        out.put("env", env);
        out.put("drives", listDrives());
        return out;
    }

    private String primaryProfile() {
        String[] active = environment.getActiveProfiles();
        if (active != null && active.length > 0) {
            return active[0];
        }
        String[] defs = environment.getDefaultProfiles();
        if (defs != null && defs.length > 0) {
            return defs[0];
        }
        return "default";
    }

    private EnvRole resolveEnv(String profile, String cwdDrive) {
        String p = profile == null ? "" : profile.toLowerCase(Locale.ROOT);
        if (p.contains("prod") || "G".equals(cwdDrive)) {
            return new EnvRole("G", "PROD", "Production-like runtime");
        }
        if ("F".equals(cwdDrive)) {
            return new EnvRole("F", "PREPROD", "Pre-production / staging runtime");
        }
        if ("E".equals(cwdDrive) || p.contains("dev")) {
            return new EnvRole("E", "DEV", "Development");
        }
        return new EnvRole(cwdDrive, p.toUpperCase(Locale.ROOT), "Unknown / local");
    }

    private List<Map<String, Object>> listDrives() {
        List<Map<String, Object>> drives = new ArrayList<>();
        drives.add(driveInfo("E", "DEV", "Development — AI agents, repos, MyAgent, MyWorkspace"));
        drives.add(driveInfo("F", "PREPROD", "Pre-production / staging runtime only"));
        drives.add(driveInfo("G", "PROD", "Production-like runtime only"));
        drives.add(driveInfo("H", "RELEASES", "Last 3 release packages (H:\\releases\\)"));
        return drives;
    }

    private Map<String, Object> driveInfo(String letter, String label, String fallbackPurpose) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("letter", letter);
        row.put("label", label);
        Path root = Path.of(letter + ":\\");
        String purpose = readPurpose(root, fallbackPurpose);
        row.put("purpose", purpose);
        try {
            if (Files.exists(root)) {
                var store = Files.getFileStore(root);
                row.put("totalBytes", store.getTotalSpace());
                row.put("freeBytes", store.getUsableSpace());
                row.put("usable", true);
            } else {
                row.put("totalBytes", 0L);
                row.put("freeBytes", 0L);
                row.put("usable", false);
            }
        } catch (IOException ex) {
            row.put("totalBytes", 0L);
            row.put("freeBytes", 0L);
            row.put("usable", false);
            row.put("error", ex.getMessage());
        }
        return row;
    }

    private String readPurpose(Path root, String fallback) {
        Path purposeFile = root.resolve("PURPOSE.md");
        try {
            if (Files.isRegularFile(purposeFile)) {
                String raw = Files.readString(purposeFile, StandardCharsets.UTF_8);
                if (!raw.isEmpty() && raw.charAt(0) == '\uFEFF') {
                    raw = raw.substring(1);
                }
                for (String line : raw.split("\\R")) {
                    String t = line.trim();
                    if (t.isEmpty() || t.startsWith("#")) {
                        continue;
                    }
                    if (t.regionMatches(true, 0, "Purpose:", 0, 8)) {
                        return t.substring(8).trim();
                    }
                    return t;
                }
            }
        } catch (IOException ignored) {
            // fallback
        }
        return fallback;
    }

    private record EnvRole(String drive, String label, String role) {}
}
