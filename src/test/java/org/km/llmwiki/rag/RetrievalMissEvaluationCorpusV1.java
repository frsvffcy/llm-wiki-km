package org.km.llmwiki.rag;

import java.util.List;

/** Versioned, deterministic corpus contract for issue #575 retrieval-miss attribution. */
final class RetrievalMissEvaluationCorpusV1 {

    static final String VERSION = "retrieval-miss-attribution-corpus-v1";
    static final int K = 8;

    static final List<CoverageCase> COVERAGE = List.of(
            new CoverageCase("exact-latin", "ZXCV-2048", "ZXCV-2048 release marker"),
            new CoverageCase("property-token", "busy_timeout", "SQLite busy_timeout tuning"),
            new CoverageCase("single-cjk", "遷移鎖", "遷移鎖排解手冊"),
            new CoverageCase("long-cjk-phrase", "分散式資料庫遷移鎖", "分散式資料庫遷移鎖排解手冊"));

    static final String HUMAN_LIKE_QUERY = "busy_timeout 這個設定要怎麼調整";
    static final List<BoundedLever> BOUNDED_LEVERS = List.of(
            new BoundedLever("exact-anchor", "busy_timeout"),
            new BoundedLever("protected-rewrite", "SQLite busy_timeout"));
    static final String MISSING_TERM_QUERY = "busy_timeout unicorn";
    static final String CORPUS_MISMATCH_QUERY = "旅行簽證火車時刻";

    private RetrievalMissEvaluationCorpusV1() {
    }

    record CoverageCase(String id, String query, String content) {
    }

    record BoundedLever(String id, String query) {
    }
}
