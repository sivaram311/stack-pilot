package com.stackpilot.manager.service;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AiDlcStatusService {

    private static final Path PHASE1_REVIEW_QUEUE =
            Path.of("E:\\MyAgent\\workflow\\aidlc\\phase1\\REVIEW-QUEUE.md");
    private static final Path PHASE2_PROPOSALS =
            Path.of("E:\\MyAgent\\workflow\\aidlc\\phase2\\proposals");

    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();

        Phase1Counts phase1 = readPhase1();
        out.put("phase1Available", phase1.available());
        out.put("phase1OpenFindings", phase1.openFindings());
        out.put("phase1TriagedFindings", phase1.triagedFindings());

        Phase2Counts phase2 = readPhase2();
        out.put("phase2Available", phase2.available());
        out.put("phase2Pending", phase2.pending());
        out.put("phase2ResolvedGo", phase2.resolvedGo());
        out.put("phase2ResolvedNoGo", phase2.resolvedNoGo());

        out.put("generatedAt", Instant.now().toString());
        return out;
    }

    private Phase1Counts readPhase1() {
        try {
            if (!Files.isRegularFile(PHASE1_REVIEW_QUEUE)) {
                return Phase1Counts.unavailable();
            }
            String raw = Files.readString(PHASE1_REVIEW_QUEUE, StandardCharsets.UTF_8);
            int open = 0;
            int triaged = 0;
            for (String line : raw.split("\\R")) {
                if (line.contains("- [ ] ")) {
                    open++;
                } else if (line.contains("- [x] ")) {
                    triaged++;
                }
            }
            return new Phase1Counts(true, open, triaged);
        } catch (IOException ex) {
            return Phase1Counts.unavailable();
        }
    }

    private Phase2Counts readPhase2() {
        try {
            if (!Files.isDirectory(PHASE2_PROPOSALS)) {
                return Phase2Counts.unavailable();
            }
            int pending = 0;
            int resolvedGo = 0;
            int resolvedNoGo = 0;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(PHASE2_PROPOSALS)) {
                for (Path entry : stream) {
                    if (!Files.isDirectory(entry)) {
                        continue;
                    }
                    if ("reports".equals(entry.getFileName().toString())) {
                        continue;
                    }
                    SignOffResult result = classifySignOff(entry.resolve("SIGN-OFF.md"));
                    switch (result) {
                        case GO -> resolvedGo++;
                        case NO_GO -> resolvedNoGo++;
                        case PENDING -> pending++;
                    }
                }
            }
            return new Phase2Counts(true, pending, resolvedGo, resolvedNoGo);
        } catch (IOException ex) {
            return Phase2Counts.unavailable();
        }
    }

    private SignOffResult classifySignOff(Path signOffFile) {
        try {
            if (!Files.isRegularFile(signOffFile)) {
                return SignOffResult.PENDING;
            }
            String raw = Files.readString(signOffFile, StandardCharsets.UTF_8);
            for (String line : raw.split("\\R")) {
                String trimmed = line.trim();
                if (matchesDecision(trimmed, "GO")) {
                    return SignOffResult.GO;
                }
                if (matchesDecision(trimmed, "NO-GO")) {
                    return SignOffResult.NO_GO;
                }
            }
            return SignOffResult.PENDING;
        } catch (IOException ex) {
            return SignOffResult.PENDING;
        }
    }

    /** Trimmed line is decision with optional 0–2 leading/trailing asterisks. */
    private static boolean matchesDecision(String trimmed, String decision) {
        int len = trimmed.length();
        int start = 0;
        while (start < len && start < 2 && trimmed.charAt(start) == '*') {
            start++;
        }
        int end = len;
        int trailing = 0;
        while (end > start && trailing < 2 && trimmed.charAt(end - 1) == '*') {
            end--;
            trailing++;
        }
        return trimmed.substring(start, end).equals(decision);
    }

    private enum SignOffResult {
        GO, NO_GO, PENDING
    }

    private record Phase1Counts(boolean available, int openFindings, int triagedFindings) {
        static Phase1Counts unavailable() {
            return new Phase1Counts(false, 0, 0);
        }
    }

    private record Phase2Counts(boolean available, int pending, int resolvedGo, int resolvedNoGo) {
        static Phase2Counts unavailable() {
            return new Phase2Counts(false, 0, 0, 0);
        }
    }
}
