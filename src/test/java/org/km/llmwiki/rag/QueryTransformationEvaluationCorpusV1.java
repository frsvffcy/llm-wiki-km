package org.km.llmwiki.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Versioned query-transformation evaluation corpus
 * ({@code query-transformation-evaluation-corpus-v1}, rewrite fixtures
 * {@code query-rewrite-fixtures-v1}) for issue #390.
 *
 * <p><strong>Non-candidate-bias methodology.</strong> Pages are reused verbatim from
 * {@link RerankEvaluationCorpusV1} — that corpus predates this issue and was not shaped for any
 * rewrite candidate. Queries reuse all 15 #316 queries and add only two shapes the question
 * side needs: a two-topic multi-intent query and an absent-topic no-evidence probe. Rewrite
 * fixtures simulate what a semantic query rewriter would plausibly emit: retrieval-style
 * keyword phrases derived from the question's meaning using general domain knowledge only
 * (abbreviation expansion such as {@code db → 資料庫}, interrogative-filler removal, technical
 * tokens kept verbatim). No fixture is tuned against corpus page text; the harness publishes
 * the projected-token overlap between every rewrite and its labeled targets so page-fitting can
 * be audited in the report. The {@code unprotectedProbe} column models the realistic failure
 * mode of a rewriter that normalizes an error code or class name into prose — it quantifies why
 * exact-token protection must be a hard adoption gate, and is never the headline candidate.
 */
final class QueryTransformationEvaluationCorpusV1 {

    static final String VERSION = "query-transformation-evaluation-corpus-v1";
    static final String REWRITE_FIXTURES_VERSION = "query-rewrite-fixtures-v1";

    static final String MULTI_INTENT_QUERY_ID = "multi-intent";
    static final String NO_EVIDENCE_QUERY_ID = "no-evidence";

    private static final String WIKI = "WIKI:";

    private QueryTransformationEvaluationCorpusV1() {
    }

    static List<GraphRetrievalGoldenCorpus.GoldenPage> pages(long restoreDocumentId) {
        return RerankEvaluationCorpusV1.pages(restoreDocumentId);
    }

    /** All 15 #316 queries plus the multi-intent and no-evidence shapes. */
    static List<GraphRetrievalGoldenCorpus.GoldenQuery> queries(String restoreChunkIdentity,
                                                               String cacheTargetIdentity) {
        List<GraphRetrievalGoldenCorpus.GoldenQuery> queries = new ArrayList<>(
                RerankEvaluationCorpusV1.queries(restoreChunkIdentity, cacheTargetIdentity));
        queries.add(new GraphRetrievalGoldenCorpus.GoldenQuery(MULTI_INTENT_QUERY_ID,
                "MULTI_INTENT", "資料庫備份排程與搜尋索引重建分別要注意什麼？",
                Set.of(WIKI + RerankEvaluationCorpusV1.DB_BACKUP,
                        WIKI + RerankEvaluationCorpusV1.SEARCH_INDEX_REBUILD),
                Set.of()));
        queries.add(new GraphRetrievalGoldenCorpus.GoldenQuery(NO_EVIDENCE_QUERY_ID,
                "NO_EVIDENCE", "旅行行程規劃要注意哪些細節？", Set.of(), Set.of()));
        return List.copyOf(queries);
    }

    /**
     * One simulated rewrite output per query. {@code protectedRewrite} is the realistic
     * token-protected candidate; {@code altRewrite} is a second plausible phrasing (only used by
     * the bounded multi-query candidate if the unlock rule fires); {@code unprotectedProbe} is
     * the degradation probe. {@code null} means the simulated provider answers {@code NO_REWRITE}
     * for that query (typed no-op, original query proceeds unchanged).
     */
    record RewriteFixture(String protectedRewrite, String altRewrite, String unprotectedProbe,
                          String rationale) {
    }

    private static final Map<String, RewriteFixture> FIXTURES = Map.ofEntries(
            Map.entry("zh-natural", new RewriteFixture("分散式資料庫 遷移鎖", "資料庫遷移鎖",
                    "資料庫同步鎖的設定方式", "疑問填充詞去除，保留主題詞；probe 以近義詞替換遷移鎖模擬改寫失真")),
            Map.entry("zh-mixed-tech", new RewriteFixture("SQLite WAL 快取行為",
                    "SQLite 資料庫 WAL 模式",
                    "資料庫寫入模式的快取調整", "技術 token verbatim 保留，去除疑問填充詞")),
            Map.entry("exact-error-code", new RewriteFixture("資料庫 ORA-12899 診斷",
                    "ORA-12899 資料庫欄位長度", "資料庫欄位長度超限的診斷",
                    "錯誤碼 verbatim 保留；probe 把 ORA-12899 一般化為欄位長度描述（unprotected 失敗模式）")),
            Map.entry("class-token", new RewriteFixture("NoSuchMethodError 資料庫",
                    "NoSuchMethodError 資料庫 classpath", "Java 資料庫執行階段方法遺失錯誤",
                    "class 名稱 verbatim 保留；probe 把 class 名稱一般化為行為描述")),
            Map.entry("exact-token-paraphrase", new RewriteFixture("jakarta.persistence 遷移 資料庫",
                    "javax persistence 資料庫遷移", "套件更名後資料庫應用程式崩潰",
                    "套件名稱 verbatim 保留；probe 完全捨棄套件名稱")),
            Map.entry("paraphrase", new RewriteFixture("資料庫 連線池 調校", "資料庫連線池大小",
                    "資料庫最大連線數建議", "db 縮寫展開、連線數量改寫為連線池語意")),
            Map.entry("exact-vs-semantic", new RewriteFixture(null, null, null,
                    "極短查詢已是最小檢索片語，任何改寫只會增加 AND 限制；typed no-op 路徑")),
            Map.entry("multi-relevant", new RewriteFixture("資料庫連線池 WAL 模式", "資料庫連線池",
                    "資料庫設定取捨建議",
                    "單一改寫無法同時讓兩個目標頁都通過 FTS AND（無共同內容詞），只能 lexical 覆蓋其一；"
                            + "完整覆蓋屬 multi-query/decomposition 範疇")),
            Map.entry("noise-high-similarity", new RewriteFixture("搜尋索引 重建 流程", "搜尋索引重建",
                    "索引壞掉要怎麼重建", "主題詞聚焦，降低高相似 noise 頁的競爭")),
            Map.entry("graph-added", new RewriteFixture("查詢效能診斷 搜尋變慢", "搜尋變慢 診斷",
                    "網站搜尋速度優化",
                    "graph-only 目標不在改寫者視界；改寫只能強化 seed 頁 lexical 命中，"
                            + "graph-only 覆蓋由 retention gate 驗證")),
            Map.entry("cross-modality", new RewriteFixture("資料庫 還原演練 執行", "資料庫還原演練",
                    "資料庫災難復原演練", "保留跨 modality 問句的 wiki 頁主題詞")),
            Map.entry("stale-negative", new RewriteFixture("資料庫 陳舊 安全頁", null, null,
                    "安全負例：任何改寫都不得使 stale 頁重新進入 Evidence（authority gate）")),
            Map.entry("already-good", new RewriteFixture("資料庫遷移鎖", null, null,
                    "已命中查詢的最小改寫；不得使既有最佳排名退步")),
            Map.entry("property-token", new RewriteFixture("資料庫 busy_timeout",
                    "SQLite 資料庫 busy_timeout",
                    "資料庫連線逾時預設數值",
                    "技術 property verbatim 保留＋填充詞去除；probe 把 busy_timeout 一般化為連線逾時")),
            Map.entry("mixed-content", new RewriteFixture("jOOQ FTS5 中文搜尋", "jOOQ 資料庫 UPSERT",
                    "資料庫套件的全文檢索注意事項",
                    "兩個目標頁無共同完整詞組，單一改寫只能 lexical 覆蓋其一（真實限制，如實量測）")),
            Map.entry(MULTI_INTENT_QUERY_ID, new RewriteFixture("資料庫備份 搜尋索引重建", "資料庫備份",
                    null,
                    "兩個互斥主題無法以單一 lexical 查詢同時精確命中（FTS AND）；single rewrite 的極限"
                            + "即 decomposition 的動機，如實量測不掩饰")),
            Map.entry(NO_EVIDENCE_QUERY_ID, new RewriteFixture("旅行 行程 規劃", null, null,
                    "語料外主題：改寫不得虛構 Evidence；no-evidence 必須維持 no-evidence")));

    static RewriteFixture fixtureFor(String queryId) {
        return FIXTURES.get(queryId);
    }
}
