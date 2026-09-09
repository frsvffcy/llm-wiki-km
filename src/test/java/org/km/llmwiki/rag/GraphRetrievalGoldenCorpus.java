package org.km.llmwiki.rag;

import java.util.List;
import java.util.Set;

/**
 * Versioned golden corpus for the graph-grounded retrieval quality benchmark.
 *
 * <p>Every relevance label uses the application-owned canonical evidence identity
 * ({@code WIKI:<knowledgeId>}); no backend record id, row id, vendor score, or insertion order
 * ever appears in the golden truth. The corpus is deliberately small but covers the modality
 * differences the benchmark must observe:
 *
 * <ul>
 *   <li><b>lexical-exact</b> — the query wording appears in the authority content, so the FTS
 *       baseline must find it (and the same page is also vector-reachable: the cross-modality
 *       duplicate case, which must collapse onto one identity in every fused mode);</li>
 *   <li><b>semantic-paraphrase</b> — the query shares no surface token with the target (English
 *       wording vs Chinese content), so only a semantic channel can reach it (the measured
 *       baseline reaches it through concept similarity, not through FTS);</li>
 *   <li><b>graph-added-discovery</b> — seeds are lexically findable, but the relevant target
 *       shares no query token and is deliberately NOT embedded, so only an admitted
 *       {@code LINKS_TO} relation can discover it; the same target is linked from two seeds
 *       (multi-path must stay one identity), a linked but irrelevant neighbor must stay
 *       observable as bounded graph noise, and a plain-text mention without a wikilink must
 *       never create a relation (production {@code MENTIONS} NO-GO, asserted as a negative);</li>
 *   <li><b>safety corpus</b> — a stale-indexed page (hash mutated after indexing) must be
 *       rejected by authority revalidation in every mode, and a second workspace's query-matching
 *       page must never leak into the active workspace results;</li>
 *   <li><b>degradation</b> — before the graph projection is rebuilt, {@code HYBRID_GRAPH} must
 *       retain the lexical + vector baseline for the same query instead of failing or
 *       masquerading as a normal empty result.</li>
 * </ul>
 *
 * <p>Limitations (must accompany every report): the corpus proves behavior for these scenarios
 * only; it does not establish a general product-quality guarantee, and graph-added discovery is
 * demonstrated for {@code LINKS_TO} over wiki pages ({@code CONTAINS}/{@code DERIVED_FROM} over
 * source documents and source-chunk evidence are covered by the correctness suites, not this
 * quality corpus).
 */
final class GraphRetrievalGoldenCorpus {

    static final String VERSION = "graph-retrieval-golden-v1";

    record GoldenPage(String knowledgeId, String title, String body, List<String> tags,
                      boolean embedded, List<Long> sources) {

        GoldenPage {
            tags = List.copyOf(tags);
            sources = List.copyOf(sources);
        }

        /** Compatibility view for corpora that only model wiki-to-wiki scenarios. */
        GoldenPage(String knowledgeId, String title, String body, List<String> tags,
                   boolean embedded) {
            this(knowledgeId, title, body, tags, embedded, List.of());
        }
    }

    record GoldenQuery(String id, String queryClass, String text, Set<String> relevant,
                       Set<String> graphOnlyRelevant) {
    }

    private static final String STALE_PAGE = "wiki-db-stale";
    private static final String FOREIGN_PAGE = "wiki-foreign-leak";
    private static final String MENTION_PAGE = "wiki-mention-negative";
    private static final String GRAPH_TARGET = "wiki-concurrency";
    private static final String COOKING_NEIGHBOR = "wiki-cooking";

    static final String WIKI = "WIKI:";

    private static final GoldenPage DB_INDEX = new GoldenPage("wiki-db-index", "資料庫索引",
            "資料庫索引的設計與維護要點。", List.of(), true);
    private static final GoldenPage DB_CAPACITY = new GoldenPage("wiki-db-capacity", "容量規劃",
            "資料庫容量規劃與成長預估。", List.of(), true);
    private static final GoldenPage LOCK_SEED = new GoldenPage("wiki-lock-seed", "資料庫鎖",
            "資料庫鎖的行為說明。\n\n- [[併發控制設計]]\n- [[烹飪筆記]]\n\n"
                    + "普通文字提到 旅遊手記 不構成 relation。",
            List.of("database"), true);
    private static final GoldenPage LOCK_GUIDE = new GoldenPage("wiki-lock-guide", "鎖指南",
            "鎖指南的重點整理。\n\n- [[併發控制設計]]", List.of(), true);
    private static final GoldenPage CONCURRENCY = new GoldenPage(GRAPH_TARGET, "併發控制設計",
            "併發控制的設計取捨。", List.of(), false);
    private static final GoldenPage COOKING = new GoldenPage(COOKING_NEIGHBOR, "烹飪筆記",
            "烹飪的基礎準備。", List.of(), false);
    private static final GoldenPage STALE = new GoldenPage(STALE_PAGE, "資料庫安全",
            "資料庫安全檢查清單。", List.of(), true);
    private static final GoldenPage MENTION = new GoldenPage(MENTION_PAGE, "旅遊手記",
            "旅遊手記的片段。", List.of(), false);
    private static final GoldenPage FOREIGN = new GoldenPage(FOREIGN_PAGE, "資料庫漫遊",
            "資料庫漫遊的外部工作區頁面。", List.of(), false);

    List<GoldenPage> pages() {
        return List.of(DB_INDEX, DB_CAPACITY, LOCK_SEED, LOCK_GUIDE, CONCURRENCY, COOKING,
                STALE, MENTION);
    }

    List<GoldenPage> foreignWorkspacePages() {
        return List.of(FOREIGN);
    }

    List<GoldenQuery> queries() {
        return List.of(
                new GoldenQuery("lexical-exact-duplicate", "LEXICAL_EXACT", "資料庫索引",
                        Set.of(WIKI + "wiki-db-index"), Set.of()),
                new GoldenQuery("semantic-paraphrase", "SEMANTIC_PARAPHRASE",
                        "database capacity planning", Set.of(WIKI + "wiki-db-capacity"),
                        Set.of()),
                new GoldenQuery("graph-added-discovery", "GRAPH_ADDED", "資料庫鎖",
                        Set.of(WIKI + "wiki-lock-seed", WIKI + "wiki-lock-guide",
                                WIKI + GRAPH_TARGET),
                        Set.of(WIKI + GRAPH_TARGET)));
    }

    String staleIdentity() {
        return WIKI + STALE_PAGE;
    }

    String foreignIdentity() {
        return WIKI + FOREIGN_PAGE;
    }

    String mentionNegativeIdentity() {
        return WIKI + MENTION_PAGE;
    }

    GoldenPage page(String knowledgeId) {
        return pages().stream().filter(page -> page.knowledgeId().equals(knowledgeId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown corpus page " + knowledgeId));
    }
}
