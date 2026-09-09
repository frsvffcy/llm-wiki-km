package org.km.llmwiki.rag;

import java.util.List;

/**
 * Versioned holdout scenarios ({@code graph-retrieval-holdout-v1}) for the fusion ranking
 * calibration. The holdout runs in its own isolated workspace. It deliberately reuses the same
 * high-level query classes as the golden corpus ({@code LEXICAL_EXACT}, {@code
 * SEMANTIC_PARAPHRASE}, {@code GRAPH_ADDED}) with different pages and content, and its graph
 * scenario intentionally reproduces the same targeted tie-break failure mechanism as the golden
 * corpus's graph-added-discovery query (a graph-only noise identity that sorts ahead of the
 * relevant vector hit). Its purpose is to
 * verify that the selected policy generalizes across fresh data for the measured failure
 * mechanism — not to cover different relation types, hops, or topology shapes; the diversified
 * relation/query-shape coverage lives in the versioned generalization evaluation corpus
 * ({@code GraphRetrievalEvaluationCorpusV2}, Issue #280). It is deliberately separate from
 * {@link GraphRetrievalGoldenCorpus}: golden corpora gate floors, holdout corpora challenge
 * selection decisions.
 */
final class GraphRetrievalHoldoutCorpus {

    static final String VERSION = "graph-retrieval-holdout-v1";

    private final List<GraphRetrievalGoldenCorpus.GoldenPage> pages = List.of(
            new GraphRetrievalGoldenCorpus.GoldenPage("holdout-db-replication", "資料庫複製",
                    "資料庫複製策略的說明文件：主從架構的同步流程。", List.of("database"), true),
            new GraphRetrievalGoldenCorpus.GoldenPage("holdout-vacuum-cache", "快取驅逐",
                    "快取驅逐策略的說明文件：容量上限與時效性的取捨。", List.of("cache"), true),
            new GraphRetrievalGoldenCorpus.GoldenPage("holdout-lock-tuning", "資料庫鎖調校",
                    "資料庫鎖調校的說明文件：[[併發調校手冊]]、[[家常食譜]] 與隔離層級的取捨。",
                    List.of("database", "lock"), true),
            new GraphRetrievalGoldenCorpus.GoldenPage("holdout-concurrency", "併發調校手冊",
                    "併發調校手冊的說明文件：鎖與隔離層級的實務調校。", List.of(), false),
            new GraphRetrievalGoldenCorpus.GoldenPage("holdout-cooking", "家常食譜",
                    "家常食譜的說明文件：簡單的家常菜做法。", List.of(), false),
            new GraphRetrievalGoldenCorpus.GoldenPage("holdout-security-noise", "資料庫安全稽核",
                    "資料庫安全稽核的說明文件：稽核日誌與權限覆查。", List.of("security"), true),
            new GraphRetrievalGoldenCorpus.GoldenPage("holdout-stale-db", "資料庫稽核修訂",
                    "資料庫稽核修訂的說明文件：修訂記錄與差異比對。", List.of("database"), true));

    /**
     * Holdout-local safety negative: the external editor republishes this page after indexing,
     * so its FTS and embedding rows go stale and every mode must reject it inside the holdout
     * workspace itself (the golden safety negatives live in the golden workspace only).
     */
    GraphRetrievalGoldenCorpus.GoldenPage stalePage() {
        return new GraphRetrievalGoldenCorpus.GoldenPage("holdout-stale-db", "資料庫稽核修訂",
                "資料庫稽核修訂的說明文件：修訂記錄與差異比對。", List.of("database"), true);
    }

    List<String> forbiddenIdentities() {
        return List.of("WIKI:wiki-foreign-leak", "WIKI:holdout-stale-db");
    }

    private final List<GraphRetrievalGoldenCorpus.GoldenQuery> queries = List.of(
            new GraphRetrievalGoldenCorpus.GoldenQuery("h-lexical-replication", "LEXICAL_EXACT",
                    "資料庫複製策略", java.util.Set.of("WIKI:holdout-db-replication"),
                    java.util.Set.of()),
            new GraphRetrievalGoldenCorpus.GoldenQuery("h-semantic-eviction", "SEMANTIC_PARAPHRASE",
                    "cache eviction policy", java.util.Set.of("WIKI:holdout-vacuum-cache"),
                    java.util.Set.of()),
            new GraphRetrievalGoldenCorpus.GoldenQuery("h-graph-lock-tuning", "GRAPH_ADDED",
                    "資料庫鎖調校",
                    java.util.Set.of("WIKI:holdout-lock-tuning", "WIKI:holdout-concurrency"),
                    java.util.Set.of("WIKI:holdout-concurrency")));

    List<GraphRetrievalGoldenCorpus.GoldenPage> pages() {
        return pages;
    }

    List<GraphRetrievalGoldenCorpus.GoldenQuery> queries() {
        return queries;
    }
}
