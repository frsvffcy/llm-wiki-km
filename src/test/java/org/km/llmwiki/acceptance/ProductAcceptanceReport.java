package org.km.llmwiki.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Bounded release-evidence report (Refs #429 §H).
 *
 * <p>Machine + human readable. Never contains secrets, raw provider payloads,
 * full canonical content dumps, API keys, or absolute local paths beyond the
 * redacted workspace label.
 */
public final class ProductAcceptanceReport {

    public enum Verdict {
        PASS, FAIL, SKIP
    }

    public record Step(String id, Verdict verdict, String detail) {
    }

    private final List<Step> steps = new ArrayList<>();
    private String executionMode = "unknown";
    private String prerequisiteNotes = "";

    public void executionMode(String mode) {
        this.executionMode = mode;
    }

    public void prerequisiteNotes(String notes) {
        this.prerequisiteNotes = notes;
    }

    public void add(String id, Verdict verdict, String detail) {
        steps.add(new Step(id, verdict, bounded(detail)));
    }

    public List<Step> steps() {
        return List.copyOf(steps);
    }

    /** FULL GO only when every step PASSes; any SKIP blocks FULL GO (no fake-green). */
    public String overall() {
        boolean anyFail = steps.stream().anyMatch(step -> step.verdict() == Verdict.FAIL);
        if (anyFail) {
            return "NO-GO";
        }
        boolean anySkip = steps.stream().anyMatch(step -> step.verdict() == Verdict.SKIP);
        if (anySkip) {
            return "CONDITIONAL";
        }
        return "FULL-GO";
    }

    public void writeTo(Path directory, String sourceSha, String javaVersion,
                        String os, String arch) {
        try {
            Files.createDirectories(directory);
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode root = mapper.createObjectNode();
            root.put("corpus", ProductAcceptanceCorpusV1.VERSION);
            root.put("procedure", ProductAcceptanceCorpusV1.PROCEDURE_VERSION);
            root.put("sourceCommit", sourceSha);
            root.put("javaVersion", javaVersion);
            root.put("os", os);
            root.put("arch", arch);
            root.put("executionMode", executionMode);
            root.put("prerequisiteNotes", bounded(prerequisiteNotes));
            root.put("overall", overall());
            root.put("generatedAt", Instant.now().toString());
            ArrayNode journeys = root.putArray("journeys");
            for (Step step : steps) {
                ObjectNode node = journeys.addObject();
                node.put("id", step.id());
                node.put("verdict", step.verdict().name());
                node.put("detail", step.detail());
            }
            Files.writeString(directory.resolve("v0.1.1-product-acceptance.json"),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n");
            Files.writeString(directory.resolve("v0.1.1-product-acceptance.md"),
                    markdown(sourceSha, javaVersion, os, arch));
        } catch (Exception failure) {
            throw new IllegalStateException("failed to write acceptance report", failure);
        }
    }

    private String markdown(String sourceSha, String javaVersion, String os, String arch) {
        StringBuilder report = new StringBuilder();
        report.append("# v0.1.1 product acceptance report\n\n");
        report.append("- corpus: `").append(ProductAcceptanceCorpusV1.VERSION).append("`\n");
        report.append("- procedure: `").append(ProductAcceptanceCorpusV1.PROCEDURE_VERSION)
                .append("`\n");
        report.append("- source: `").append(sourceSha).append("`\n");
        report.append("- runtime: `").append(javaVersion).append(" / ").append(os).append(" / ")
                .append(arch).append("`\n");
        report.append("- execution mode: `").append(executionMode).append("`\n");
        report.append("- overall: **").append(overall()).append("**\n\n");
        if (!prerequisiteNotes.isBlank()) {
            report.append("## Prerequisites\n\n").append(prerequisiteNotes).append("\n\n");
        }
        report.append("## Journeys\n\n");
        report.append("| journey | verdict | detail |\n| --- | --- | --- |\n");
        for (Step step : steps) {
            report.append("| ").append(step.id()).append(" | ").append(step.verdict())
                    .append(" | ").append(oneLine(step.detail())).append(" |\n");
        }
        report.append("\nOverall `").append(overall())
                .append("`: any SKIP blocks FULL-GO (no fake-green).\n");
        report.append("Report is evidence only, never runtime authority; no secrets stored.\n");
        return report.toString();
    }

    private static String bounded(String value) {
        if (value == null) {
            return "";
        }
        String redacted = value
                .replaceAll("(?i)bearer\\s+\\S+", "Bearer <redacted>")
                .replaceAll("(?i)api[_-]?key[\"'\\s:=]+\\S+", "api-key=<redacted>");
        return redacted.length() <= 500 ? redacted : redacted.substring(0, 500) + "…";
    }

    private static String oneLine(String value) {
        return value.replace('|', '/').replace('\n', ' ');
    }
}
