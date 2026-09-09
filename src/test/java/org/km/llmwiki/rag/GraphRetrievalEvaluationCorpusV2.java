package org.km.llmwiki.rag;

import java.util.List;
import java.util.Set;

/**
 * Versioned diversified evaluation corpus ({@code graph-retrieval-evaluation-v2}) for the graph
 * ranking generalization gate (Issue #280). Where the golden corpus and the holdout corpus cover
 * {@code LINKS_TO} over wiki pages only, this corpus exercises every admitted relation path the
 * production projection can build:
 *
 * <ul>
 *   <li><b>LINKS_TO_1HOP</b> — a linked but unembedded, lexically unreachable target must be
 *       discovered through a direct {@code LINKS_TO} edge, next to a cross-modality duplicate
 *       (the duplicate matches the query both lexically and semantically and must collapse onto
 *       one identity);</li>
 *   <li><b>LINKS_TO_2HOP</b> — the target sits two directed hops away through an intermediate
 *       hub (both hub and target are neither embedded nor lexically reachable), exercising
 *       depth-2 traversal with bounded cross-scenario noise;</li>
 *   <li><b>LINKS_TO_MULTI_TARGET</b> — one seed links to two distinct graph-only targets, so
 *       multi-target discovery must not crowd either target out of the budget;</li>
 *   <li><b>DERIVED_FROM_CONTAINS_2HOP</b> — the query page cites a source document in its
 *       frontmatter ({@code sources}); the graph path runs
 *       {@code DERIVED_FROM} → {@code CONTAINS} to a source chunk that is neither FTS-indexed
 *       nor embedded, so only the graph channel can reach the {@code SOURCE_CHUNK} citation
 *       authority (the intermediate {@code SOURCE_DOCUMENT} node is non-citation authority and
 *       must be rejected by admission);</li>
 *   <li><b>MENTION_PLAIN_TEXT_NO_GO</b> — a plain-text mention without a wikilink must never
 *       create a relation ({@code MENTIONS} is production NO-GO); the mentioned page is a
 *       forbidden identity for every mode.</li>
 * </ul>
 *
 * <p>Design constraint (production semantics, not fixture freedom): the vector channel is a
 * thresholdless top-K — every embedded page is a candidate for every query regardless of concept
 * overlap — the graph admission budget admits at most four graph evidence items per query and
 * truncates the remainder in traversal order, the fused evidence budget serves {@code k=8}
 * identities, and the calibrated production policy damps the graph channel below the
 * lexical/vector channels. The corpus therefore keeps exactly two embedded pages (the
 * {@code LINKS_TO_1HOP} cross-modality duplicate and the stale safety page), so every query's
 * baseline channels contribute at most two distinct identities and each query yields at most
 * two admission-worthy traversal candidates; every graph-only relevant target then fits inside
 * the served window under both the baseline and the selected policy. Corpus size beyond that
 * measures budget pressure, not ranking quality, and page identities are prefixed so that
 * equal-contribution ties resolve deterministically toward the scenario seed.
 *
 * <p>{@code TAGGED_WITH} edges are also present in the projection (every page carries a tag),
 * but TAG entities have no outgoing edges, so a tag can never contribute a traversal path. The
 * tag therefore exercises the dead-end case by construction; a false graph-only credit through a
 * tag would surface as a safety violation, not as a discovery metric.
 *
 * <p>Safety negatives: a stale-indexed page (hash mutated after indexing) must be rejected by
 * authority revalidation in every mode; a deleted legacy document's chunk identity must never be
 * admitted (the deleted document is excluded from the canonical projection); a second
 * workspace's query-matching page must never leak into the active workspace results.
 *
 * <p>Limitations: this corpus measures generalization (relation-path coverage, budget fit, and
 * no-regression across policies), not ranking discrimination — the targeted tie-break failure
 * mechanism is owned by the versioned holdout corpus ({@code GraphRetrievalHoldoutCorpus}).
 * Like every quality corpus, it proves behavior for these scenarios only and does not establish
 * a general product-quality guarantee.
 */
final class GraphRetrievalEvaluationCorpusV2 {

    static final String VERSION = "graph-retrieval-evaluation-v2";

    private static final String LOCK_SEED = "eval2-lock-seed";
    private static final String LOCK_TARGET = "eval2-lock-target";
    private static final String LOCK_DUP = "eval2-sync-node-list";
    private static final String HOP_SEED = "eval2-hop-seed";
    private static final String HOP_MID = "eval2-hop-mid";
    private static final String HOP_TARGET = "eval2-hop-target";
    private static final String MULTI_SEED = "eval2-multi-seed";
    private static final String MULTI_TARGET_A = "eval2-multi-target-a";
    private static final String MULTI_TARGET_B = "eval2-multi-target-b";
    private static final String DERIVATION_SEED = "eval2-derivation-seed";
    static final String STALE_PAGE = "eval2-stale-hardening";
    private static final String ARCHIVE_SEED = "eval2-archive-seed";
    private static final String MENTION_ONLY_PAGE = "eval2-mention-only";

    List<GraphRetrievalGoldenCorpus.GoldenPage> pages(long restoreDocumentId) {
        return List.of(
                new GraphRetrievalGoldenCorpus.GoldenPage(LOCK_SEED, "分散式鎖診斷",
                        "分散式鎖診斷的說明文件，指向 [[容錯切換手冊]] 與 [[同步節點清單]]。",
                        List.of("分散式系統"), false, List.of()),
                new GraphRetrievalGoldenCorpus.GoldenPage(LOCK_TARGET, "容錯切換手冊",
                        "容錯切換的標準作業程序與檢核清單。",
                        List.of("分散式系統"), false, List.of()),
                new GraphRetrievalGoldenCorpus.GoldenPage(LOCK_DUP, "同步節點清單",
                        "整理分散式鎖的事件清單，呼應分散式鎖診斷的結論。",
                        List.of("分散式系統"), true, List.of()),
                new GraphRetrievalGoldenCorpus.GoldenPage(HOP_SEED, "資料庫索引調校",
                        "資料庫索引調校的說明文件：[[容量盤點手札]]。",
                        List.of("資料庫"), false, List.of()),
                new GraphRetrievalGoldenCorpus.GoldenPage(HOP_MID, "容量盤點手札",
                        "容量盤點的工作筆記，問題線索整理在 [[鎖競爭診斷]]。",
                        List.of("資料庫"), false, List.of()),
                new GraphRetrievalGoldenCorpus.GoldenPage(HOP_TARGET, "鎖競爭診斷",
                        "鎖競爭的診斷紀錄與觀察重點。",
                        List.of("資料庫"), false, List.of()),
                new GraphRetrievalGoldenCorpus.GoldenPage(MULTI_SEED, "快取搜尋策略",
                        "快取搜尋策略的說明文件：[[災難復原手冊]]、[[演練記錄彙編]]。",
                        List.of("營運備援"), false, List.of()),
                new GraphRetrievalGoldenCorpus.GoldenPage(MULTI_TARGET_A, "災難復原手冊",
                        "災難復原的程序手冊與聯絡機制。",
                        List.of("營運備援"), false, List.of()),
                new GraphRetrievalGoldenCorpus.GoldenPage(MULTI_TARGET_B, "演練記錄彙編",
                        "演練排程的紀錄彙整與後續追蹤。",
                        List.of("營運備援"), false, List.of()),
                new GraphRetrievalGoldenCorpus.GoldenPage(DERIVATION_SEED, "資料庫還原",
                        "資料庫還原的說明文件，引用外部還原演練文件。",
                        List.of("資料庫"), false, List.of(restoreDocumentId)),
                new GraphRetrievalGoldenCorpus.GoldenPage(STALE_PAGE, "資料庫強化",
                        "資料庫強化的說明文件與檢核重點。",
                        List.of("資料庫"), true, List.of()),
                new GraphRetrievalGoldenCorpus.GoldenPage(ARCHIVE_SEED, "資料庫封存",
                        "資料庫封存的說明文件，討論專利地圖彙整的整理方式。",
                        List.of("資料庫"), false, List.of()),
                new GraphRetrievalGoldenCorpus.GoldenPage(MENTION_ONLY_PAGE, "專利地圖彙整",
                        "專利地圖的彙整文件與範本。",
                        List.of("資料庫"), false, List.of()));
    }

    /** {@code restoreChunkIdentity} is the resolved {@code SOURCE_CHUNK:<id>} citation target. */
    List<GraphRetrievalGoldenCorpus.GoldenQuery> queries(String restoreChunkIdentity) {
        return List.of(
                new GraphRetrievalGoldenCorpus.GoldenQuery("link-1hop", "LINKS_TO_1HOP",
                        "分散式鎖診斷",
                        Set.of(wiki(LOCK_SEED), wiki(LOCK_DUP), wiki(LOCK_TARGET)),
                        Set.of(wiki(LOCK_TARGET))),
                new GraphRetrievalGoldenCorpus.GoldenQuery("wiki-2hop", "LINKS_TO_2HOP",
                        "資料庫索引調校",
                        Set.of(wiki(HOP_SEED), wiki(HOP_TARGET)),
                        Set.of(wiki(HOP_TARGET))),
                new GraphRetrievalGoldenCorpus.GoldenQuery("multi-target", "LINKS_TO_MULTI_TARGET",
                        "快取搜尋策略",
                        Set.of(wiki(MULTI_SEED), wiki(MULTI_TARGET_A), wiki(MULTI_TARGET_B)),
                        Set.of(wiki(MULTI_TARGET_A), wiki(MULTI_TARGET_B))),
                new GraphRetrievalGoldenCorpus.GoldenQuery("source-chunk-2hop",
                        "DERIVED_FROM_CONTAINS_2HOP", "資料庫還原",
                        Set.of(wiki(DERIVATION_SEED), restoreChunkIdentity),
                        Set.of(restoreChunkIdentity)),
                new GraphRetrievalGoldenCorpus.GoldenQuery("mention-no-go",
                        "MENTION_PLAIN_TEXT_NO_GO", "資料庫封存",
                        Set.of(wiki(ARCHIVE_SEED)),
                        Set.of()));
    }

    /**
     * Identities that no mode may ever retrieve: the stale page (authority revalidation), the
     * mention-only page ({@code MENTIONS} NO-GO), and the deleted legacy document's chunk
     * identity (deleted documents are excluded from the canonical projection).
     */
    List<String> safetyIdentities(String deletedChunkIdentity) {
        return List.of(wiki(STALE_PAGE), wiki(MENTION_ONLY_PAGE), deletedChunkIdentity);
    }

    private static String wiki(String knowledgeId) {
        return "WIKI:" + knowledgeId;
    }
}
