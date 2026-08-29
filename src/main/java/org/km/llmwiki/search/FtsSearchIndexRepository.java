package org.km.llmwiki.search;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;

/**
 * Controlled adapter for the SQLite FTS5 operational indexes.
 *
 * <p>FTS5 virtual tables are intentionally accessed through bound jOOQ plain SQL because MATCH,
 * bm25 and virtual-table rowids are not portable jOOQ DSL constructs. Callers never provide SQL,
 * table names or MATCH expressions; workspace predicates and query escaping stay in this boundary.
 */
@Repository
public class FtsSearchIndexRepository {

    private static final String KNOWLEDGE = "KNOWLEDGE";
    private static final String SOURCE = "SOURCE";

    private final DSLContext dsl;

    public FtsSearchIndexRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Transactional
    public void upsertKnowledge(KnowledgeSearchDocument document) {
        upsertIdentity(KNOWLEDGE, document.workspaceId(), document.knowledgeId(),
                rowId -> insertKnowledge(rowId, document));
    }

    @Transactional
    public void upsertSource(SourceSearchDocument document) {
        upsertIdentity(SOURCE, document.workspaceId(), document.stableId(),
                rowId -> insertSource(rowId, document));
    }

    /** Clears one workspace projection without touching Wiki or Source Chunk source tables. */
    @Transactional
    public void clearWorkspace(long workspaceId) {
        clearCorpus(KNOWLEDGE, workspaceId);
        clearCorpus(SOURCE, workspaceId);
    }

    @Transactional
    public void clearKnowledge(long workspaceId) {
        clearCorpus(KNOWLEDGE, workspaceId);
    }

    @Transactional
    public void clearSource(long workspaceId) {
        clearCorpus(SOURCE, workspaceId);
    }

    /** Explicit full reset primitive used by a future rebuild job and by isolated tests. */
    @Transactional
    public void clearAll() {
        dsl.execute("DELETE FROM knowledge_fts");
        dsl.execute("DELETE FROM source_fts");
        dsl.execute("DELETE FROM search_index_identity");
    }

    /** Rebuildability foundation: clear a corpus and repopulate it from caller-supplied authority data. */
    @Transactional
    public void rebuildKnowledge(long workspaceId, Collection<KnowledgeSearchDocument> documents) {
        clearKnowledge(workspaceId);
        documents.forEach(document -> requireWorkspace(workspaceId, document.workspaceId(), "knowledge"));
        documents.forEach(this::upsertKnowledge);
    }

    /** Rebuildability foundation: clear a corpus and repopulate it from Source Chunk projections. */
    @Transactional
    public void rebuildSource(long workspaceId, Collection<SourceSearchDocument> documents) {
        clearSource(workspaceId);
        documents.forEach(document -> requireWorkspace(workspaceId, document.workspaceId(), "source"));
        documents.forEach(this::upsertSource);
    }

    /** Repository-level MATCH primitive; REST search and snippets belong to a later Story. */
    @Transactional(readOnly = true)
    public List<SearchIndexMatch> matchKnowledge(long workspaceId, String query) {
        String expression = FtsMatchQuery.literalExpression(normalizeQuery(query));
        return dsl.fetch("""
                        SELECT knowledge_fts.workspace_id AS workspace_id,
                               knowledge_fts.knowledge_id AS stable_id,
                               knowledge_fts.title AS title,
                               knowledge_fts.content AS searchable_text,
                               knowledge_fts.markdown_path AS location,
                               knowledge_fts.page_type AS page_type,
                               knowledge_fts.content_hash AS content_hash,
                               bm25(knowledge_fts) AS rank
                        FROM knowledge_fts
                        JOIN search_index_identity identity
                          ON identity.corpus = 'KNOWLEDGE'
                         AND identity.workspace_id = knowledge_fts.workspace_id
                         AND identity.stable_id = knowledge_fts.knowledge_id
                         AND identity.fts_rowid = knowledge_fts.rowid
                        WHERE knowledge_fts MATCH {0}
                          AND knowledge_fts.workspace_id = {1}
                          AND knowledge_fts.page_status = 'PUBLISHED'
                        ORDER BY rank ASC, identity.fts_rowid ASC
                        """, expression, workspaceId)
                .map(record -> new SearchIndexMatch(
                        KNOWLEDGE,
                        record.get("workspace_id", Long.class),
                        record.get("stable_id", String.class),
                        record.get("title", String.class),
                        record.get("searchable_text", String.class),
                        record.get("location", String.class),
                        record.get("page_type", String.class),
                        record.get("content_hash", String.class),
                        record.get("rank", Double.class)));
    }

    @Transactional(readOnly = true)
    public List<SearchIndexMatch> matchSource(long workspaceId, String query) {
        String expression = FtsMatchQuery.literalExpression(normalizeQuery(query));
        return dsl.fetch("""
                        SELECT source_fts.workspace_id AS workspace_id,
                               source_fts.source_chunk_id AS stable_id,
                               NULL AS title,
                               source_fts.normalized_content AS searchable_text,
                               source_fts.heading_path AS location,
                               NULL AS page_type,
                               source_fts.content_hash AS content_hash,
                               bm25(source_fts) AS rank
                        FROM source_fts
                        JOIN search_index_identity identity
                          ON identity.corpus = 'SOURCE'
                         AND identity.workspace_id = source_fts.workspace_id
                         AND identity.stable_id = source_fts.source_chunk_id
                         AND identity.fts_rowid = source_fts.rowid
                        WHERE source_fts MATCH {0}
                          AND source_fts.workspace_id = {1}
                        ORDER BY rank ASC, identity.fts_rowid ASC
                        """, expression, workspaceId)
                .map(record -> new SearchIndexMatch(
                        SOURCE,
                        record.get("workspace_id", Long.class),
                        record.get("stable_id", String.class),
                        record.get("title", String.class),
                        record.get("searchable_text", String.class),
                        record.get("location", String.class),
                        record.get("page_type", String.class),
                        record.get("content_hash", String.class),
                        record.get("rank", Double.class)));
    }

    private void upsertIdentity(String corpus, long workspaceId, String stableId,
                                RowInserter inserter) {
        Long existingRowId = existingRowId(corpus, workspaceId, stableId);
        long rowId = existingRowId == null ? nextRowId(corpus) : existingRowId;
        if (existingRowId != null) {
            dsl.execute("DELETE FROM " + table(corpus) + " WHERE rowid = {0}", rowId);
        }
        inserter.insert(rowId);
        String now = now();
        if (existingRowId == null) {
            dsl.execute("""
                            INSERT INTO search_index_identity
                                (corpus, workspace_id, stable_id, fts_rowid, indexed_at)
                            VALUES ({0}, {1}, {2}, {3}, {4})
                            """, corpus, workspaceId, stableId, rowId, now);
        } else {
            dsl.execute("""
                            UPDATE search_index_identity
                               SET indexed_at = {0}
                             WHERE corpus = {1} AND workspace_id = {2} AND stable_id = {3}
                            """, now, corpus, workspaceId, stableId);
        }
    }

    private void insertKnowledge(long rowId, KnowledgeSearchDocument document) {
        dsl.execute("""
                        INSERT INTO knowledge_fts
                            (rowid, workspace_id, knowledge_id, title, content, normalized_title,
                             markdown_path, page_type, page_status, content_hash)
                        VALUES ({0}, {1}, {2}, {3}, {4}, {5}, {6}, {7}, {8}, {9})
                        """, rowId, document.workspaceId(), document.knowledgeId(), document.title(),
                document.content(), document.normalizedTitle(), document.markdownPath(), document.pageType(),
                document.pageStatus(), document.contentHash());
    }

    private void insertSource(long rowId, SourceSearchDocument document) {
        dsl.execute("""
                        INSERT INTO source_fts
                            (rowid, workspace_id, source_chunk_id, document_id, chunk_no,
                             normalized_content, section, heading_path, content_hash)
                        VALUES ({0}, {1}, {2}, {3}, {4}, {5}, {6}, {7}, {8})
                        """, rowId, document.workspaceId(), document.stableId(), document.documentId(),
                document.chunkNo(), document.normalizedContent(), document.section(), document.headingPath(),
                document.contentHash());
    }

    private void clearCorpus(String corpus, long workspaceId) {
        dsl.fetch("""
                        SELECT fts_rowid
                          FROM search_index_identity
                         WHERE corpus = {0} AND workspace_id = {1}
                        """, corpus, workspaceId)
                .forEach(record -> dsl.execute("DELETE FROM " + table(corpus) + " WHERE rowid = {0}",
                        record.get("fts_rowid", Long.class)));
        dsl.execute("DELETE FROM search_index_identity WHERE corpus = {0} AND workspace_id = {1}",
                corpus, workspaceId);
    }

    private Long existingRowId(String corpus, long workspaceId, String stableId) {
        var record = dsl.fetchOne("""
                        SELECT fts_rowid
                          FROM search_index_identity
                         WHERE corpus = {0} AND workspace_id = {1} AND stable_id = {2}
                        """, corpus, workspaceId, stableId);
        return record == null ? null : record.get("fts_rowid", Long.class);
    }

    private Long nextRowId(String corpus) {
        var record = dsl.fetchOne("""
                        SELECT COALESCE(MAX(fts_rowid), 0) + 1 AS next_rowid
                          FROM search_index_identity
                         WHERE corpus = {0}
                        """, corpus);
        return record.get("next_rowid", Long.class);
    }

    private static String table(String corpus) {
        return switch (corpus) {
            case KNOWLEDGE -> "knowledge_fts";
            case SOURCE -> "source_fts";
            default -> throw new IllegalArgumentException("Unknown FTS corpus: " + corpus);
        };
    }

    private static String normalizeQuery(String query) {
        return query == null ? null : Normalizer.normalize(query, Normalizer.Form.NFC);
    }

    private static void requireWorkspace(long expected, long actual, String corpus) {
        if (expected != actual) {
            throw new IllegalArgumentException(corpus + " rebuild document belongs to another workspace");
        }
    }

    private static String now() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now());
    }

    @FunctionalInterface
    private interface RowInserter {
        void insert(long rowId);
    }
}
