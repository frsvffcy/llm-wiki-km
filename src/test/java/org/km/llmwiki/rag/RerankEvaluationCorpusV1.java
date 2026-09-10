package org.km.llmwiki.rag;

import java.util.List;
import java.util.Set;

/**
 * Versioned second-stage reranking evaluation corpus ({@code rerank-evaluation-corpus-v1}).
 *
 * <p>It reuses the #272/#280 production-equivalent fixture path (real workspace, real FTS with
 * {@code cjk-bigram-v1}, deterministic concept embeddings over the real vector candidate
 * service, real ArcadeDB projection with traversal/admission/fusion) and adds the query shapes
 * a second-stage reranker must survive: exact technical tokens, zh-TW natural language,
 * paraphrase, cross-modality, graph-added authority, high-similarity noise, and stale/foreign
 * negatives. Relevance labels are declared per query; metrics stay identity-level and
 * candidate-pool changes must stay separable from pure reordering.
 */
final class RerankEvaluationCorpusV1 {

    static final String VERSION = "rerank-evaluation-corpus-v1";
    static final String STALE_PAGE = "wiki-rerank-stale";

    private static final String WIKI = "WIKI:";

    static final String MIGRATION_LOCK = "wiki-migration-lock";
    static final String ORA_CODE = "wiki-ora-12899";
    static final String DB_BACKUP = "wiki-db-backup";
    static final String NOSUCHMETHOD = "wiki-nosuchmethod";
    static final String JAKARTA = "wiki-jakarta-migration";
    static final String CONNECTION_POOL = "wiki-connection-pool";
    static final String JOOQ_UPSERT = "wiki-jooq-upsert";
    static final String SQLITE_WAL = "wiki-sqlite-wal";
    static final String SEARCH_INDEX_REBUILD = "wiki-search-index-rebuild";
    static final String INDEX_DESIGN = "wiki-db-index-design";
    static final String CONCURRENCY_OVERVIEW = "wiki-concurrency-overview";
    static final String CJK_SEARCH_NOTES = "wiki-cjk-search-notes";
    static final String SEARCH_TUNING_HUB = "wiki-search-tuning-hub";
    static final String CACHE_INVALIDATION_TARGET = "wiki-cache-invalidation-notes";
    static final String RESTORE_WIKI = "wiki-restore-runbook";
    static final String COOKING = "wiki-cooking-noise";

    private RerankEvaluationCorpusV1() {
    }

    static List<GraphRetrievalGoldenCorpus.GoldenPage> pages(long restoreDocumentId) {
        return List.of(
                new GraphRetrievalGoldenCorpus.GoldenPage(MIGRATION_LOCK, "資料庫遷移鎖",
                        "分散式資料庫的 schema 遷移需要鎖。The migration lock must be held by "
                                + "exactly one node, and the lock holder publishes its generation.",
                        List.of("database", "migration"), true, List.of()),
                new GraphRetrievalGoldenCorpus.GoldenPage(ORA_CODE, "ORA-12899 欄位長度診斷",
                        "寫入資料庫超出欄位長度時會出現 ORA-12899: value too large for column。"
                                + "先檢查 NLS_LENGTH_SEMANTICS 與實際欄位大小。",
                        List.of("database", "troubleshooting"), true, List.of()),
                new GraphRetrievalGoldenCorpus.GoldenPage(DB_BACKUP, "資料庫備份與還原",
                        "資料庫的備份節奏、還原演練與異地副本配置；備份視窗不得影響線上服務。",
                        List.of("database", "backup"), true, List.of()),
                new GraphRetrievalGoldenCorpus.GoldenPage(NOSUCHMETHOD, "NoSuchMethodError 排查",
                        "NoSuchMethodError 通常代表 classpath 上有兩個不同版本的同一個 class，在資料庫驅動升級時最常見；"
                                + "以 mvn dependency:tree 找出衝突來源。",
                        List.of("troubleshooting", "java"), true, List.of()),
                new GraphRetrievalGoldenCorpus.GoldenPage(JAKARTA, "jakarta.persistence 遷移",
                        "javax.persistence 更名為 jakarta.persistence 後，舊 import 會在運行時"
                                + "引發 NoSuchMethodError；遷移時務必同步更新 ORM 與資料庫連線設定。",
                        List.of("java", "migration"), true, List.of()),
                new GraphRetrievalGoldenCorpus.GoldenPage(CONNECTION_POOL, "資料庫連線池調校",
                        "資料庫連線池大小的調校以觀測為先：等待時間、閒置回收與最大連線數要一起看。",
                        List.of("database", "tuning"), true, List.of()),
                new GraphRetrievalGoldenCorpus.GoldenPage(JOOQ_UPSERT, "jOOQ UPSERT 語意",
                        "jOOQ 的 onConflict 可以表達 UPSERT；SQLite 資料庫需要 excluded(...) 欄位引用。",
                        List.of("database", "jooq"), true, List.of()),
                new GraphRetrievalGoldenCorpus.GoldenPage(SQLITE_WAL, "SQLite WAL 與 busy_timeout",
                        "SQLite 資料庫的 WAL 模式、page cache 快取行為，加上 positive busy_timeout 才安全。",
                        List.of("sqlite", "database"), true, List.of()),
                new GraphRetrievalGoldenCorpus.GoldenPage(SEARCH_INDEX_REBUILD, "搜尋索引重建流程",
                        "FTS 索引的重建要走 processing job；重建期間的 admission 不得重疊。",
                        List.of("search", "operations"), true, List.of()),
                new GraphRetrievalGoldenCorpus.GoldenPage(CONCURRENCY_OVERVIEW, "併發安全概述",
                        "併發問題先分類：可見性、互斥、還是順序；再決定鎖或無鎖結構。",
                        List.of("concurrency", "design"), true, List.of()),
                new GraphRetrievalGoldenCorpus.GoldenPage(CJK_SEARCH_NOTES, "中文搜尋筆記",
                        "FTS5 的 cjk-bigram-v1 讓繁中查詢以雙字詞命中；拉丁 token 保持精確比對。"
                                + "下表是常見查詢形態：\n\n| 型態 | 例子 |\n|---|---|\n| 錯誤碼 | ORA-12899 |\n"
                                + "| class | NoSuchMethodError |\n| 套件 | jOOQ |",
                        List.of("search", "cjk"), true, List.of()),
                new GraphRetrievalGoldenCorpus.GoldenPage(SEARCH_TUNING_HUB, "查詢效能診斷",
                        "查詢效能診斷的三步驟：搜尋變慢先看執行計畫與快取命中率，再往索引與連線池追。"
                                + "相關整理見 [[快取失效筆記]]。",
                        List.of("search", "tuning"), true, List.of()),
                new GraphRetrievalGoldenCorpus.GoldenPage(CACHE_INVALIDATION_TARGET, "快取失效筆記",
                        "快取失效的策略筆記：主動失效被動到期各有適用場景。",
                        List.of("cache"), false, List.of()),
                new GraphRetrievalGoldenCorpus.GoldenPage(RESTORE_WIKI, "還原演練筆記",
                        "還原演練的目的：驗證資料庫備份真的可以還原；真正的執行細節在對應的來源文件。",
                        List.of("backup", "operations"), true, List.of(restoreDocumentId)),
                new GraphRetrievalGoldenCorpus.GoldenPage(COOKING, "熱炒小訣竅",
                        "烹飪熱炒的重點是鍋氣與火候控制。",
                        List.of("cooking"), true, List.of()),
                new GraphRetrievalGoldenCorpus.GoldenPage(STALE_PAGE, "陳舊安全頁",
                        "這一頁的資料庫內容會被外部編輯使 indexed hash 變陳舊，任何 mode 都不得回傳。",
                        List.of("database"), true, List.of()));
    }

    /**
     * Queries with declared relevance. {@code relevant} identities may reference runtime-resolved
     * source-chunk ids (the {@code SOURCE_CHUNK} placeholder is replaced by the caller).
     */
    static List<GraphRetrievalGoldenCorpus.GoldenQuery> queries(String restoreChunkIdentity,
                                                               String cacheTargetIdentity) {
        return List.of(
                new GraphRetrievalGoldenCorpus.GoldenQuery("zh-natural", "ZH_NATURAL",
                        "分散式資料庫的遷移鎖要怎麼設定才安全？",
                        Set.of(WIKI + MIGRATION_LOCK), Set.of()),
                new GraphRetrievalGoldenCorpus.GoldenQuery("zh-mixed-tech", "ZH_MIXED_TECH",
                        "SQLite WAL 模式下的快取行為要怎麼調整？",
                        Set.of(WIKI + SQLITE_WAL), Set.of()),
                new GraphRetrievalGoldenCorpus.GoldenQuery("exact-error-code", "EXACT_TOKEN",
                        "資料庫寫入出現 ORA-12899 錯誤要怎麼診斷？",
                        Set.of(WIKI + ORA_CODE), Set.of()),
                new GraphRetrievalGoldenCorpus.GoldenQuery("class-token", "EXACT_TOKEN",
                        "NoSuchMethodError 這種錯誤在資料庫應用裡常見嗎？",
                        Set.of(WIKI + NOSUCHMETHOD), Set.of()),
                new GraphRetrievalGoldenCorpus.GoldenQuery("exact-token-paraphrase", "EXACT_TOKEN",
                        "jakarta.persistence 遷移之後為什麼資料庫應用會炸掉？",
                        Set.of(WIKI + JAKARTA), Set.of()),
                new GraphRetrievalGoldenCorpus.GoldenQuery("paraphrase", "SEMANTIC",
                        "db 連線數量的調校建議有哪些？",
                        Set.of(WIKI + CONNECTION_POOL), Set.of()),
                new GraphRetrievalGoldenCorpus.GoldenQuery("exact-vs-semantic", "EXACT_VS_SEMANTIC",
                        "資料庫鎖", Set.of(WIKI + MIGRATION_LOCK), Set.of()),
                new GraphRetrievalGoldenCorpus.GoldenQuery("multi-relevant", "MULTI_RELEVANT",
                        "資料庫連線池與 WAL 模式的取捨",
                        Set.of(WIKI + CONNECTION_POOL, WIKI + SQLITE_WAL), Set.of()),
                new GraphRetrievalGoldenCorpus.GoldenQuery("noise-high-similarity", "NOISE",
                        "搜尋索引的重建要怎麼做？",
                        Set.of(WIKI + SEARCH_INDEX_REBUILD), Set.of()),
                new GraphRetrievalGoldenCorpus.GoldenQuery("graph-added", "GRAPH_ADDED",
                        "查詢效能診斷——搜尋變慢要怎麼辦？",
                        Set.of(WIKI + SEARCH_TUNING_HUB, cacheTargetIdentity),
                        Set.of(cacheTargetIdentity)),
                new GraphRetrievalGoldenCorpus.GoldenQuery("cross-modality", "CROSS_MODALITY",
                        "還原演練的執行節奏——資料庫還原要做什麼？",
                        Set.of(WIKI + RESTORE_WIKI, restoreChunkIdentity), Set.of()),
                new GraphRetrievalGoldenCorpus.GoldenQuery("stale-negative", "SAFETY_NEGATIVE",
                        "資料庫陳舊安全頁的內容", Set.of(), Set.of()),
                new GraphRetrievalGoldenCorpus.GoldenQuery("already-good", "ALREADY_GOOD",
                        "資料庫遷移鎖的持有者",
                        Set.of(WIKI + MIGRATION_LOCK), Set.of()),
                new GraphRetrievalGoldenCorpus.GoldenQuery("property-token", "EXACT_TOKEN",
                        "資料庫連線的 busy_timeout 預設值要怎麼設定？",
                        Set.of(WIKI + SQLITE_WAL), Set.of()),
                new GraphRetrievalGoldenCorpus.GoldenQuery("mixed-content", "EXACT_TOKEN",
                        "jOOQ 與 SQLite FTS5 的中文搜尋要注意什麼？",
                        Set.of(WIKI + CJK_SEARCH_NOTES, WIKI + JOOQ_UPSERT), Set.of()));
    }

    /** Identities that must never be retrieved in any mode or by any reranker. */
    static List<String> safetyIdentities(String legacyChunkIdentity, String foreignIdentity) {
        return List.of(WIKI + STALE_PAGE, legacyChunkIdentity, foreignIdentity);
    }
}
