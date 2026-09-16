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
 * Typed daily-workflow finding report (Refs #467 §C).
 *
 * <p>Every finding carries a {@link Category} — never free text alone — plus
 * the journey/fixture identity, typed expected/observed outcomes,
 * reproducibility, affected surface, current authority / source identity, safe
 * bounded metrics, trigger mapping and blocker flag.
 *
 * <p>Privacy boundary: findings contain only synthetic fixture ids, typed
 * counters and category labels. Raw private content, filenames from a real
 * vault, absolute paths, raw questions/answers and secrets must never be
 * recorded here; {@link #add} redacts credential-shaped and path-shaped
 * material and stays bounded.
 */
public final class DailyWorkflowFindingReport {

    /** #467 §C finding schema (PRODUCT_BUG maps to a corrective Issue). */
    public enum Category {
        PRODUCT_BUG,
        UX_FRICTION,
        OPERABILITY,
        RETRIEVAL_QUALITY,
        KNOWLEDGE_MAINTENANCE,
        CANDIDATE_TRIGGER,
        NO_EVIDENCE
    }

    public record Finding(String journey, String fixtureId, Category category, boolean blocker,
                          boolean reproducible, String expected, String observed,
                          String affectedSurface, String authority, String metrics,
                          String triggerMapping) {
    }

    private final List<Finding> findings = new ArrayList<>();
    private String executionMode = "unknown";
    private String prerequisiteNotes = "";

    public void executionMode(String mode) {
        this.executionMode = mode;
    }

    public void prerequisiteNotes(String notes) {
        this.prerequisiteNotes = notes;
    }

    public void add(String journey, String fixtureId, Category category, boolean blocker,
                    boolean reproducible, String expected, String observed,
                    String affectedSurface, String authority, String metrics,
                    String triggerMapping) {
        findings.add(new Finding(journey, fixtureId, category, blocker, reproducible,
                bounded(expected), bounded(observed), bounded(affectedSurface),
                bounded(authority), bounded(metrics), bounded(triggerMapping)));
    }

    public List<Finding> findings() {
        return List.copyOf(findings);
    }

    public boolean hasBlocker() {
        return findings.stream().anyMatch(Finding::blocker);
    }

    /**
     * Overall verdict for the validation run: {@code NO-GO} when any blocker
     * finding exists, otherwise {@code FULL-GO} when every required journey
     * recorded at least one reproducible finding (including explicit
     * {@code NO_EVIDENCE} when a candidate shows no trigger).
     */
    public String overall() {
        if (hasBlocker()) {
            return "NO-GO";
        }
        return findings.isEmpty() ? "NO-GO" : "FULL-GO";
    }

    public void writeTo(Path directory, String sourceSha, String javaVersion,
                        String os, String arch) {
        try {
            Files.createDirectories(directory);
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode root = mapper.createObjectNode();
            root.put("corpus", DailyWorkflowCorpusV2.VERSION);
            root.put("procedure", DailyWorkflowCorpusV2.PROCEDURE_VERSION);
            root.put("sourceCommit", sourceSha);
            root.put("javaVersion", javaVersion);
            root.put("os", os);
            root.put("arch", arch);
            root.put("executionMode", executionMode);
            root.put("prerequisiteNotes", bounded(prerequisiteNotes));
            root.put("overall", overall());
            root.put("hasBlocker", hasBlocker());
            root.put("generatedAt", Instant.now().toString());
            ArrayNode nodes = root.putArray("findings");
            for (Finding finding : findings) {
                ObjectNode node = nodes.addObject();
                node.put("journey", finding.journey());
                node.put("fixtureId", finding.fixtureId());
                node.put("category", finding.category().name());
                node.put("blocker", finding.blocker());
                node.put("reproducible", finding.reproducible());
                node.put("expected", finding.expected());
                node.put("observed", finding.observed());
                node.put("affectedSurface", finding.affectedSurface());
                node.put("authority", finding.authority());
                node.put("metrics", finding.metrics());
                node.put("triggerMapping", finding.triggerMapping());
            }
            Files.writeString(directory.resolve("daily-workflow-v2-findings.json"),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n");
            Files.writeString(directory.resolve("daily-workflow-v2-findings.md"),
                    markdown(sourceSha, javaVersion, os, arch));
        } catch (Exception failure) {
            throw new IllegalStateException("failed to write daily-workflow finding report", failure);
        }
    }

    private String markdown(String sourceSha, String javaVersion, String os, String arch) {
        StringBuilder report = new StringBuilder();
        report.append("# daily-workflow validation v2 findings\n\n");
        report.append("- corpus: `").append(DailyWorkflowCorpusV2.VERSION).append("`\n");
        report.append("- procedure: `").append(DailyWorkflowCorpusV2.PROCEDURE_VERSION)
                .append("`\n");
        report.append("- source: `").append(sourceSha).append("`\n");
        report.append("- runtime: `").append(javaVersion).append(" / ").append(os).append(" / ")
                .append(arch).append("`\n");
        report.append("- execution mode: `").append(executionMode).append("`\n");
        report.append("- overall: **").append(overall()).append("**\n\n");
        if (!prerequisiteNotes.isBlank()) {
            report.append("## Prerequisites\n\n").append(prerequisiteNotes).append("\n\n");
        }
        report.append("## Findings\n\n");
        report.append("| journey | fixture | category | blocker | reproducible | expected | observed |\n");
        report.append("| --- | --- | --- | --- | --- | --- | --- |\n");
        for (Finding finding : findings) {
            report.append("| ").append(oneLine(finding.journey()))
                    .append(" | ").append(oneLine(finding.fixtureId()))
                    .append(" | ").append(finding.category())
                    .append(" | ").append(finding.blocker())
                    .append(" | ").append(finding.reproducible())
                    .append(" | ").append(oneLine(finding.expected()))
                    .append(" | ").append(oneLine(finding.observed()))
                    .append(" |\n");
        }
        report.append("\nOverall `").append(overall())
                .append("`: any blocker finding forces NO-GO (corrective Issue required).\n");
        report.append("Report carries synthetic fixture ids and typed counters only; ")
                .append("no private content, paths, raw Q/A or secrets stored.\n");
        return report.toString();
    }

    static String bounded(String value) {
        if (value == null) {
            return "";
        }
        String redacted = value
                .replaceAll("(?i)bearer\\s+\\S+", "Bearer <redacted>")
                .replaceAll("(?i)api[_-]?key[\"'\\s:=]+\\S+", "api-key=<redacted>")
                .replaceAll("/Users/[^\\s\"']*", "<redacted-path>")
                .replaceAll("/home/[^\\s\"']*", "<redacted-path>")
                .replaceAll("/tmp/[^\\s\"']*", "<redacted-path>")
                .replaceAll("[A-Za-z]:\\\\[^\\s\"']*", "<redacted-path>");
        return redacted.length() <= 500 ? redacted : redacted.substring(0, 500) + "…";
    }

    private static String oneLine(String value) {
        return value.replace('|', '/').replace('\n', ' ');
    }
}
