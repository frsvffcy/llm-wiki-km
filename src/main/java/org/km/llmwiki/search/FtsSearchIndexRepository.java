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

    /** Removes one workspace-scoped Source Chunk projection and its stable rowid mapping. */
    @Transactional
    public void deleteSource(long workspaceId, long sourceChunkId) {
        String stableId = Long.toString(sourceChunkId);
        Long rowId = existingRowId(SOURCE, workspaceId, stableId);
        if (rowId == null) {
            return;
        }
        dsl.execute("DELETE FROM source_fts WHERE rowid = {0}", rowId);
        dsl.execute("""
                DELETE FROM search_index_identity
                 WHERE corpus = 'SOURCE' AND workspace_id = {0} AND stable_id = {1}
                """, workspaceId, stableId);
    }

    /** Removes every Source projection for one document without crossing workspace boundaries. */
    @Transactional
    public void deleteSourceDocument(long workspaceId, long documentId) {
        List<Long> rowIds = dsl.fetch("""
                        SELECT identity.fts_rowid
                          FROM search_index_identity identity
                          JOIN source_fts
                            ON source_fts.rowid = identity.fts_rowid
                           AND source_fts.workspace_id = identity.workspace_id
                           AND source_fts.source_chunk_id = identity.stable_id
                         WHERE identity.corpus = 'SOURCE'
                           AND identity.workspace_id = {0}
                           AND source_fts.document_id = {1}
                        """, workspaceId, documentId)
                .getValues("fts_rowid", Long.class);
        for (Long rowId : rowIds) {
            dsl.execute("DELETE FROM source_fts WHERE rowid = {0}", rowId);
            dsl.execute("""
                    DELETE FROM search_index_identity
                     WHERE corpus = 'SOURCE' AND workspace_id = {0} AND fts_rowid = {1}
                    """, workspaceId, rowId);
        }
    }

    /** Lists existing logical Source Chunk identities for one workspace-scoped document. */
    @Transactional(readOnly = true)
    public List<Long> findSourceChunkIds(long workspaceId, long documentId) {
        return dsl.fetch("""
                        SELECT source_fts.source_chunk_id
                          FROM source_fts
                          JOIN search_index_identity identity
                            ON identity.fts_rowid = source_fts.rowid
                           AND identity.workspace_id = source_fts.workspace_id
                           AND identity.stable_id = source_fts.source_chunk_id
                         WHERE identity.corpus = 'SOURCE'
                           AND source_fts.workspace_id = {0}
                           AND source_fts.document_id = {1}
                         ORDER BY source_fts.rowid
                        """, workspaceId, documentId)
                .map(record -> Long.parseLong(record.get("source_chunk_id", String.class)));
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
        return matchSourceEvidence(workspaceId, query).stream()
                .map(match -> new SearchIndexMatch(SOURCE, match.workspaceId(),
                        Long.toString(match.sourceChunkId()), null, match.normalizedContent(),
                        match.headingPath(), null, match.contentHash(), match.rank()))
                .toList();
    }

    /** Evidence-search projection with stable provenance back to the authoritative Source Chunk. */
    @Transactional(readOnly = true)
    public List<SourceSearchEvidenceMatch> matchSourceEvidence(long workspaceId, String query) {
        String expression = FtsMatchQuery.literalExpression(normalizeQuery(query));
        return dsl.fetch("""
                        SELECT source_fts.workspace_id AS workspace_id,
                               source_fts.source_chunk_id AS source_chunk_id,
                               source_fts.document_id AS document_id,
                               source_fts.chunk_no AS chunk_no,
                               source_fts.page_no AS page_no,
                               source_fts.section AS section,
                               source_fts.heading_path AS heading_path,
                               source_fts.normalized_content AS normalized_content,
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
                .map(record -> new SourceSearchEvidenceMatch(
                        Long.parseLong(record.get("source_chunk_id", String.class)),
                        record.get("workspace_id", Long.class),
                        record.get("document_id", Long.class),
                        record.get("chunk_no", Integer.class),
                        record.get("page_no", Integer.class),
                        record.get("section", String.class),
                        record.get("heading_path", String.class),
                        record.get("normalized_content", String.class),
                        record.get("content_hash", String.class),
                        record.get("rank", Double.class)));
    }

    @Transactional(readOnly = true)
    List<WikiIndexProjection> listKnowledgeProjections(long workspaceId) {
        return dsl.fetch("""
                        SELECT knowledge_fts.rowid AS row_id,
                               identity.fts_rowid IS NOT NULL AS identity_valid,
                               knowledge_fts.knowledge_id AS knowledge_id,
                               knowledge_fts.title AS title,
                               knowledge_fts.content AS content,
                               knowledge_fts.normalized_title AS normalized_title,
                               knowledge_fts.markdown_path AS markdown_path,
                               knowledge_fts.page_type AS page_type,
                               knowledge_fts.page_status AS page_status,
                               knowledge_fts.content_hash AS content_hash
                          FROM knowledge_fts
                          LEFT JOIN search_index_identity identity
                            ON identity.corpus = 'KNOWLEDGE'
                           AND identity.workspace_id = knowledge_fts.workspace_id
                           AND identity.stable_id = knowledge_fts.knowledge_id
                           AND identity.fts_rowid = knowledge_fts.rowid
                         WHERE knowledge_fts.workspace_id = {0}
                         ORDER BY knowledge_fts.knowledge_id, knowledge_fts.rowid
                        """, workspaceId)
                .map(record -> new WikiIndexProjection(
                        record.get("row_id", Long.class),
                        Boolean.TRUE.equals(record.get("identity_valid", Boolean.class)),
                        record.get("knowledge_id", String.class), record.get("title", String.class),
                        record.get("content", String.class), record.get("normalized_title", String.class),
                        record.get("markdown_path", String.class), record.get("page_type", String.class),
                        record.get("page_status", String.class), record.get("content_hash", String.class)));
    }

    @Transactional(readOnly = true)
    List<SourceIndexProjection> listSourceProjections(long workspaceId) {
        return dsl.fetch("""
                        SELECT source_fts.rowid AS row_id,
                               identity.fts_rowid IS NOT NULL AS identity_valid,
                               source_fts.source_chunk_id AS source_chunk_id,
                               source_fts.document_id AS document_id,
                               source_fts.chunk_no AS chunk_no,
                               source_fts.page_no AS page_no,
                               source_fts.normalized_content AS normalized_content,
                               source_fts.section AS section,
                               source_fts.heading_path AS heading_path,
                               source_fts.content_hash AS content_hash
                          FROM source_fts
                          LEFT JOIN search_index_identity identity
                            ON identity.corpus = 'SOURCE'
                           AND identity.workspace_id = source_fts.workspace_id
                           AND identity.stable_id = source_fts.source_chunk_id
                           AND identity.fts_rowid = source_fts.rowid
                         WHERE source_fts.workspace_id = {0}
                         ORDER BY CAST(source_fts.source_chunk_id AS INTEGER), source_fts.rowid
                        """, workspaceId)
                .map(record -> new SourceIndexProjection(
                        record.get("row_id", Long.class),
                        Boolean.TRUE.equals(record.get("identity_valid", Boolean.class)),
                        record.get("source_chunk_id", String.class),
                        record.get("document_id", Long.class), record.get("chunk_no", Integer.class),
                        record.get("page_no", Integer.class), record.get("normalized_content", String.class),
                        record.get("section", String.class), record.get("heading_path", String.class),
                        record.get("content_hash", String.class)));
    }

    @Transactional(readOnly = true)
    long countDanglingIdentities(long workspaceId, String corpus) {
        String controlledCorpus = switch (corpus) {
            case KNOWLEDGE -> KNOWLEDGE;
            case SOURCE -> SOURCE;
            default -> throw new IllegalArgumentException("Unknown FTS corpus: " + corpus);
        };
        return dsl.fetchOne("""
                        SELECT COUNT(*) AS total
                          FROM search_index_identity identity
                          LEFT JOIN %s fts ON fts.rowid = identity.fts_rowid
                         WHERE identity.workspace_id = {0}
                           AND identity.corpus = {1}
                           AND fts.rowid IS NULL
                        """.formatted(table(controlledCorpus)), workspaceId, controlledCorpus)
                .get("total", Long.class);
    }

    /** Counts authoritative Published Wiki matches for the workspace and controlled page-type filter. */
    @Transactional(readOnly = true)
    public long countWikiSearch(long workspaceId, String query, String pageType) {
        String expression = FtsMatchQuery.literalExpression(normalizeQuery(query));
        return dsl.fetchOne("""
                        SELECT COUNT(*) AS total
                          FROM knowledge_fts
                          JOIN search_index_identity identity
                            ON identity.corpus = 'KNOWLEDGE'
                           AND identity.workspace_id = knowledge_fts.workspace_id
                           AND identity.stable_id = knowledge_fts.knowledge_id
                           AND identity.fts_rowid = knowledge_fts.rowid
                          JOIN knowledge_page page
                            ON page.workspace_id = knowledge_fts.workspace_id
                           AND page.knowledge_id = knowledge_fts.knowledge_id
                           AND page.status = 'PUBLISHED'
                         WHERE knowledge_fts MATCH {0}
                           AND knowledge_fts.workspace_id = {1}
                           AND knowledge_fts.page_status = 'PUBLISHED'
                           AND ({2} IS NULL OR page.type = {2})
                        """, expression, workspaceId, pageType)
                .get("total", Long.class);
    }

    /**
     * Returns the leading Wiki candidates. BM25 uses an explicit title boost and remains
     * internal: the application layer converts the per-corpus order into a comparable score.
     */
    @Transactional(readOnly = true)
    public List<WikiFtsSearchMatch> searchWiki(long workspaceId, String query,
                                               String pageType, int limit) {
        String expression = FtsMatchQuery.literalExpression(normalizeQuery(query));
        return dsl.fetch("""
                        SELECT knowledge_fts.workspace_id AS workspace_id,
                               knowledge_fts.knowledge_id AS knowledge_id,
                               page.title AS title,
                               page.type AS page_type,
                               page.markdown_path AS markdown_path,
                               page.revision AS revision,
                               snippet(knowledge_fts, -1, '<mark>', '</mark>', '…', 24) AS snippet,
                               bm25(knowledge_fts, 0.0, 0.0, 8.0, 1.0,
                                    0.0, 0.0, 0.0, 0.0, 0.0, 0.0) AS raw_rank
                          FROM knowledge_fts
                          JOIN search_index_identity identity
                            ON identity.corpus = 'KNOWLEDGE'
                           AND identity.workspace_id = knowledge_fts.workspace_id
                           AND identity.stable_id = knowledge_fts.knowledge_id
                           AND identity.fts_rowid = knowledge_fts.rowid
                          JOIN knowledge_page page
                            ON page.workspace_id = knowledge_fts.workspace_id
                           AND page.knowledge_id = knowledge_fts.knowledge_id
                           AND page.status = 'PUBLISHED'
                         WHERE knowledge_fts MATCH {0}
                           AND knowledge_fts.workspace_id = {1}
                           AND knowledge_fts.page_status = 'PUBLISHED'
                           AND ({2} IS NULL OR page.type = {2})
                         ORDER BY raw_rank ASC, knowledge_fts.knowledge_id ASC
                         LIMIT {3}
                        """, expression, workspaceId, pageType, limit)
                .map(record -> new WikiFtsSearchMatch(
                        record.get("workspace_id", Long.class),
                        record.get("knowledge_id", String.class),
                        record.get("title", String.class),
                        record.get("page_type", String.class),
                        record.get("markdown_path", String.class),
                        record.get("revision", Integer.class),
                        record.get("snippet", String.class),
                        record.get("raw_rank", Double.class)));
    }

    /** Counts Source Chunk matches constrained to one workspace and optional document. */
    @Transactional(readOnly = true)
    public long countSourceSearch(long workspaceId, String query, Long documentId) {
        String expression = FtsMatchQuery.literalExpression(normalizeQuery(query));
        return dsl.fetchOne("""
                        SELECT COUNT(*) AS total
                          FROM source_fts
                          JOIN search_index_identity identity
                            ON identity.corpus = 'SOURCE'
                           AND identity.workspace_id = source_fts.workspace_id
                           AND identity.stable_id = source_fts.source_chunk_id
                           AND identity.fts_rowid = source_fts.rowid
                          JOIN document document
                            ON document.id = source_fts.document_id
                           AND document.workspace_id = source_fts.workspace_id
                          JOIN source_chunk chunk
                            ON chunk.id = source_fts.source_chunk_id
                           AND chunk.document_id = document.id
                         WHERE source_fts MATCH {0}
                           AND source_fts.workspace_id = {1}
                           AND ({2} IS NULL OR document.id = {2})
                        """, expression, workspaceId, documentId)
                .get("total", Long.class);
    }

    /** Returns the leading Source Chunk candidates in deterministic BM25 order. */
    @Transactional(readOnly = true)
    public List<SourceFtsSearchMatch> searchSource(long workspaceId, String query,
                                                   Long documentId, int limit) {
        String expression = FtsMatchQuery.literalExpression(normalizeQuery(query));
        return dsl.fetch("""
                        SELECT source_fts.workspace_id AS workspace_id,
                               CAST(source_fts.source_chunk_id AS INTEGER) AS source_chunk_id,
                               document.id AS document_id,
                               COALESCE(document.original_file_name, document.file_name) AS document_name,
                               source_fts.chunk_no AS chunk_no,
                               source_fts.page_no AS page_no,
                               source_fts.section AS section,
                               source_fts.heading_path AS heading_path,
                               snippet(source_fts, -1, '<mark>', '</mark>', '…', 24) AS snippet,
                               bm25(source_fts) AS raw_rank
                          FROM source_fts
                          JOIN search_index_identity identity
                            ON identity.corpus = 'SOURCE'
                           AND identity.workspace_id = source_fts.workspace_id
                           AND identity.stable_id = source_fts.source_chunk_id
                           AND identity.fts_rowid = source_fts.rowid
                          JOIN document document
                            ON document.id = source_fts.document_id
                           AND document.workspace_id = source_fts.workspace_id
                          JOIN source_chunk chunk
                            ON chunk.id = source_fts.source_chunk_id
                           AND chunk.document_id = document.id
                         WHERE source_fts MATCH {0}
                           AND source_fts.workspace_id = {1}
                           AND ({2} IS NULL OR document.id = {2})
                         ORDER BY raw_rank ASC, source_fts.source_chunk_id ASC
                         LIMIT {3}
                        """, expression, workspaceId, documentId, limit)
                .map(record -> new SourceFtsSearchMatch(
                        record.get("workspace_id", Long.class),
                        record.get("source_chunk_id", Long.class),
                        record.get("document_id", Long.class),
                        record.get("document_name", String.class),
                        record.get("chunk_no", Integer.class),
                        record.get("page_no", Integer.class),
                        record.get("section", String.class),
                        record.get("heading_path", String.class),
                        record.get("snippet", String.class),
                        record.get("raw_rank", Double.class)));
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
                            (rowid, workspace_id, source_chunk_id, document_id, chunk_no, page_no,
                             normalized_content, section, heading_path, content_hash)
                        VALUES ({0}, {1}, {2}, {3}, {4}, {5}, {6}, {7}, {8}, {9})
                        """, rowId, document.workspaceId(), document.stableId(), document.documentId(),
                document.chunkNo(), document.pageNo(), document.normalizedContent(), document.section(),
                document.headingPath(), document.contentHash());
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
