package org.km.llmwiki.ai.answer;

import org.km.llmwiki.rag.EvidenceBundle;
import org.km.llmwiki.rag.EvidenceItem;
import org.km.llmwiki.rag.EvidenceKind;
import org.km.llmwiki.rag.EvidenceWorkspace;
import org.km.llmwiki.rag.RetrievalMode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * Versioned deterministic corpus for the Answer Context Compaction evaluation
 * ({@code answer-context-compaction-corpus-v1}). Every case is a reproducible
 * EvidenceBundle plus its supporting facts; supporting-fact retention is measured by exact
 * substring containment in the projected provider context. Mandatory challenge cases
 * (tail fact, large table, source code, CJK, conflicting evidence) are deliberately sized so
 * the current per-item truncation baseline demonstrably loses their facts, exposing the
 * limitation the candidate policies must beat without violating any evidence/citation
 * invariant.
 */
final class AnswerContextCompactionCorpusV1 {

    static final String VERSION = "answer-context-compaction-corpus-v1";

    private static final EvidenceWorkspace WORKSPACE = new EvidenceWorkspace(7, "Compaction Eval");

    /** Content kinds covered by the corpus; mandatory kinds gate candidate adoption. */
    enum ContentKind {
        PROSE, TABLE, CODE, CJK, MIXED_LANGUAGE, SHORT, CONFLICTING, GRAPH_ADDED, STALE_NEGATIVE
    }

    record CompactionCase(String caseId, Set<ContentKind> kinds, boolean mandatory,
                          List<String> supportingFacts, List<EvidenceItem> evidences,
                          double retentionFloor, String challenge) {
    }

    private AnswerContextCompactionCorpusV1() {
    }

    static List<CompactionCase> cases() {
        return List.of(
                new CompactionCase("single-fact", Set.of(ContentKind.PROSE), false,
                        List.of("The refresh interval is 300 seconds."),
                        List.of(wiki("single-fact", "Sync Settings", "vault/sync.md", 2,
                                prologue("Sync Settings", 400)
                                        + " The refresh interval is 300 seconds."
                                        + prologue("Sync Settings", 400))),
                        1.0d, "single evidence with one clear fact"),
                new CompactionCase("multi-evidence", Set.of(ContentKind.PROSE), false,
                        List.of("Replication uses quorum writes.",
                                "Backups run nightly at 02:00."),
                        List.of(
                                wiki("multi-a", "Replication", "vault/replication.md", 1,
                                        prologue("Replication", 900)
                                                + " Replication uses quorum writes."
                                                + prologue("Replication", 900)),
                                wiki("multi-b", "Backups", "vault/backups.md", 1,
                                        prologue("Backups", 900)
                                                + " Backups run nightly at 02:00."
                                                + prologue("Backups", 900))),
                        1.0d, "answer requires synthesis across two evidences"),
                new CompactionCase("cross-document", Set.of(ContentKind.PROSE), false,
                        List.of("The ingest pipeline is rate limited to 50 events per second.",
                                "Retention of raw events is 30 days."),
                        List.of(
                                wiki("cross-wiki", "Ingest", "vault/ingest.md", 3,
                                        prologue("Ingest", 800)
                                                + " The ingest pipeline is rate limited to 50 events per second."
                                                + prologue("Ingest", 800)),
                                source(61L, 910L, "retention.pdf", 2, 9, "Retention",
                                        "Operations > Retention",
                                        prologue("Retention", 800)
                                                + " Retention of raw events is 30 days."
                                                + prologue("Retention", 800))),
                        1.0d, "supporting facts live in different Wiki/Source documents"),
                new CompactionCase("long-prose", Set.of(ContentKind.PROSE), false,
                        List.of("The migration lock must be held by exactly one node."),
                        List.of(wiki("long-prose", "Migration", "vault/migration.md", 4,
                                prologue("Migration", 2_500)
                                        + " The migration lock must be held by exactly one node."
                                        + prologue("Migration", 3_500))),
                        0.0d, "supporting fact sits mid-prose; window policies may honestly lose it"),
                new CompactionCase("tail-fact", Set.of(ContentKind.PROSE), true,
                        List.of("Only the final worker owns the shutdown handshake."),
                        List.of(wiki("tail-fact", "Shutdown", "vault/shutdown.md", 1,
                                prologue("Shutdown", 4_500)
                                        + " Only the final worker owns the shutdown handshake.")),
                        1.0d, "mandatory: the only supporting fact is at the very end"),
                new CompactionCase("large-table", Set.of(ContentKind.TABLE), true,
                        List.of("| eu-central | active | cold standby |"),
                        List.of(source(71L, 920L, "regions.md", 1, 1, "Regions",
                                "Deployment > Regions",
                                tableHeader() + tableRows(170)
                                        + "| eu-central | active | cold standby |\n"
                                        + tableRows(20))),
                        1.0d, "mandatory: table header and relevant row are far apart"),
                new CompactionCase("source-code", Set.of(ContentKind.CODE), true,
                        List.of("--allow-partition"),
                        List.of(source(81L, 930L, "rollout.sh", 1, null, null, null,
                                codePrologue().repeat(19)
                                        + "rollout_apply --env prod --stage canary \\\n"
                                        + "  --allow-partition \\\n"
                                        + "  --confirm-rollback-window 300s\n")),
                        1.0d, "mandatory: correctness-critical CLI tokens must survive"),
                new CompactionCase("cjk", Set.of(ContentKind.CJK), true,
                        List.of("快取層的重新整理間隔是三百秒。"),
                        List.of(wiki("cjk-page", "快取設計", "vault/cache.md", 2,
                                cjkPrologue("快取設計", 4_600)
                                        + "快取層的重新整理間隔是三百秒。")),
                        1.0d, "mandatory: long zh-TW prose; CJK code-point safety and tail facts"),
                new CompactionCase("mixed-language", Set.of(ContentKind.MIXED_LANGUAGE), false,
                        List.of("上線閘門使用 Deployment gate deployment-gate-v2。"),
                        List.of(wiki("mixed", "部署流程", "vault/deploy.md", 3,
                                mixedPrologue(1_800)
                                        + "上線閘門使用 Deployment gate deployment-gate-v2。"
                                        + mixedPrologue(1_800))),
                        1.0d, "zh-TW prose interleaved with English technical tokens"),
                new CompactionCase("conflicting-evidence", Set.of(ContentKind.CONFLICTING), true,
                        List.of("Design A keeps the legacy importer enabled.",
                                "Design B removes the legacy importer entirely."),
                        List.of(
                                wiki("conflict-a", "Design A", "vault/design-a.md", 1,
                                        prologue("Design A", 900)
                                                + " Design A keeps the legacy importer enabled."
                                                + prologue("Design A", 900)),
                                wiki("conflict-b", "Design B", "vault/design-b.md", 1,
                                        prologue("Design B", 900)
                                                + " Design B removes the legacy importer entirely."
                                                + prologue("Design B", 900))),
                        1.0d, "mandatory: compaction must not smooth away conflicting claims"),
                new CompactionCase("single-supporting-sentence", Set.of(ContentKind.PROSE), false,
                        List.of("The incident bridge is dialed from the on-call calendar."),
                        List.of(wiki("boiler", "Runbook", "vault/runbook.md", 1,
                                prologue("Runbook", 3_200)
                                        + " The incident bridge is dialed from the on-call calendar."
                                        + prologue("Runbook", 3_200))),
                        0.0d, "one supporting sentence inside heavy boilerplate; NO-OP is legal"),
                new CompactionCase("graph-added-evidence", Set.of(ContentKind.GRAPH_ADDED), false,
                        List.of("Graph-only pages still carry canonical wiki identity."),
                        List.of(
                                wiki("graph-seed", "Graph Seed", "vault/graph-seed.md", 1,
                                        prologue("Graph Seed", 700)
                                                + " Graph-only pages still carry canonical wiki identity."
                                                + prologue("Graph Seed", 700)),
                                wiki("graph-discovered", "Graph Target", "vault/graph-target.md", 1,
                                        prologue("Graph Target", 1_200))),
                        1.0d, "graph-discovered evidence must keep its canonical identity"),
                new CompactionCase("stale-not-current-negative", Set.of(ContentKind.STALE_NEGATIVE),
                        true,
                        List.of("Stale evidence is dropped before context assembly."),
                        List.of(wiki("stale-safe", "Stale Guard", "vault/stale-guard.md", 1,
                                prologue("Stale Guard", 1_000)
                                        + " Stale evidence is dropped before context assembly.")),
                        1.0d, "mandatory: rejected candidates must never re-enter; the current item keeps a measurable fact"),
                new CompactionCase("already-short", Set.of(ContentKind.SHORT), true,
                        List.of("Short evidence must stay byte-identical."),
                        List.of(wiki("short-page", "Short", "vault/short.md", 1,
                                "Short evidence must stay byte-identical.")),
                        1.0d, "mandatory: no forced compression of already-short evidence"));
    }

    static EvidenceBundle bundle(List<EvidenceItem> items) {
        return bundleWithQuery(items, "compaction evaluation");
    }

    /**
     * Re-baseline variant (#326): the ask query drives the exact-anchor policy's scoring, so
     * the rerank reorder can actually engage on multi-item cases instead of silently tying.
     */
    /** Re-baseline fixture helper (#326): constructs a wiki evidence item from raw parts. */
    static org.km.llmwiki.rag.EvidenceItem exposedWiki(String id, String title, String path,
                                                       String content) {
        return wiki(id, title, path, 1, content);
    }

    static EvidenceBundle bundleWithQuery(List<EvidenceItem> items, String query) {
        return new EvidenceBundle(query, RetrievalMode.HYBRID_FTS, WORKSPACE,
                items, new org.km.llmwiki.rag.EvidenceBudget(8, 12_000, items.size(),
                items.stream().mapToInt(item -> item.content().codePointCount(0, item.content().length()))
                        .sum(), 0, false),
                items.size(), 0, items.isEmpty(),
                RetrievalDiagnosticsHolder.LEXICAL);
    }

    private static String prologue(String title, int codePoints) {
        StringBuilder builder = new StringBuilder();
        int index = 0;
        while (builder.codePointCount(0, builder.length()) < codePoints) {
            builder.append("This paragraph of the ").append(title)
                    .append(" runbook describes operational background detail number ")
                    .append(index).append(" that is deliberately verbose for evaluation. ");
            index++;
        }
        return "\n" + builder;
    }

    private static String cjkPrologue(String title, int codePoints) {
        StringBuilder builder = new StringBuilder();
        int index = 0;
        while (builder.codePointCount(0, builder.length()) < codePoints) {
            builder.append("「").append(title).append("」第").append(index)
                    .append("段：此段為評估用的一般作業背景說明，內容刻意冗長以製造截斷壓力，")
                    .append("並涵蓋中文標點與段落結構。");
            index++;
        }
        return "\n" + builder;
    }

    private static String mixedPrologue(int codePoints) {
        StringBuilder builder = new StringBuilder();
        int index = 0;
        while (builder.codePointCount(0, builder.length()) < codePoints) {
            builder.append("部署流程第").append(index)
                    .append("段：deployment pipeline stages run in order, and each stage ")
                    .append("records its own audit trail with 中文註解 mixed inline. ");
            index++;
        }
        return "\n" + builder;
    }

    private static String tableHeader() {
        return "| region | status | replication |\n|---|---|---|\n";
    }

    private static String tableRows(int count) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < count; index++) {
            builder.append("| region-").append(index)
                    .append(" | active | same-region replica |\n");
        }
        return builder.toString();
    }

    private static String codePrologue() {
        return "# rollout helper\n\n```bash\n"
                + "set -euo pipefail\ncheck_preflight --strict\ncache_warm --background\n"
                + "verify_inventory --fail-closed\ndrain_watchers --timeout 30s\n"
                + "snapshot_state --tag pre-rollout\nvalidate_quorum --min 2\n";
    }

    private static EvidenceItem wiki(String id, String title, String path, int revision,
                                     String content) {
        return new EvidenceItem(EvidenceKind.WIKI, id, WORKSPACE, 0.5d, content,
                contentSnippet(content), false, sha256(content), id, title, "CONCEPT", path,
                revision, null, null, null, null, null, null, null);
    }

    private static EvidenceItem source(long chunkId, long documentId, String documentName,
                                       int chunkNo, Integer pageNo, String section,
                                       String headingPath, String content) {
        return new EvidenceItem(EvidenceKind.SOURCE_CHUNK, Long.toString(chunkId), WORKSPACE,
                0.4d, content, contentSnippet(content), false, sha256(content), null, null,
                null, null, null, chunkId, documentId, documentName, chunkNo, pageNo, section,
                headingPath);
    }

    private static String contentSnippet(String content) {
        int end = content.offsetByCodePoints(0, Math.min(80, content.codePointCount(0, content.length())));
        return content.substring(0, end);
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Local diagnostics holder so the corpus does not depend on rag internals beyond the bundle. */
    private static final class RetrievalDiagnosticsHolder {
        private static final org.km.llmwiki.rag.RetrievalDiagnostics LEXICAL =
                org.km.llmwiki.rag.RetrievalDiagnostics.lexical();
    }
}
