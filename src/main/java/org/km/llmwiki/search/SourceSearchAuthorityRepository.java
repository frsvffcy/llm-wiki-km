package org.km.llmwiki.search;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

import static org.km.llmwiki.persistence.jooq.generated.Tables.DOCUMENT;
import static org.km.llmwiki.persistence.jooq.generated.Tables.SOURCE_CHUNK;
import static org.jooq.impl.DSL.coalesce;

/** jOOQ read boundary from authoritative document and source_chunk rows. */
@Repository
public class SourceSearchAuthorityRepository {

    private final DSLContext dsl;

    public SourceSearchAuthorityRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<SourceSearchAuthorityDocument> findDocument(long workspaceId, long documentId) {
        var document = dsl.select(DOCUMENT.ID,
                        coalesce(DOCUMENT.ORIGINAL_FILE_NAME, DOCUMENT.FILE_NAME).as("document_name"),
                        DOCUMENT.STATUS, DOCUMENT.PARSE_STATUS)
                .from(DOCUMENT)
                .where(DOCUMENT.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                .and(DOCUMENT.ID.eq(Math.toIntExact(documentId)))
                .fetchOne();
        if (document == null) {
            return Optional.empty();
        }
        List<SourceSearchAuthorityChunk> chunks = dsl.select(
                        SOURCE_CHUNK.ID,
                        SOURCE_CHUNK.CHUNK_NO,
                        SOURCE_CHUNK.PAGE_NO,
                        SOURCE_CHUNK.SECTION,
                        SOURCE_CHUNK.HEADING_PATH,
                        SOURCE_CHUNK.NORMALIZED_CONTENT,
                        SOURCE_CHUNK.CONTENT_HASH)
                .from(SOURCE_CHUNK)
                .where(SOURCE_CHUNK.DOCUMENT_ID.eq(Math.toIntExact(documentId)))
                .orderBy(SOURCE_CHUNK.CHUNK_NO.asc(), SOURCE_CHUNK.ID.asc())
                .fetch(record -> new SourceSearchAuthorityChunk(
                        record.get(SOURCE_CHUNK.ID).longValue(),
                        record.get(SOURCE_CHUNK.CHUNK_NO),
                        record.get(SOURCE_CHUNK.PAGE_NO),
                        record.get(SOURCE_CHUNK.SECTION),
                        record.get(SOURCE_CHUNK.HEADING_PATH),
                        record.get(SOURCE_CHUNK.NORMALIZED_CONTENT),
                        record.get(SOURCE_CHUNK.CONTENT_HASH)));
        return Optional.of(new SourceSearchAuthorityDocument(workspaceId, documentId,
                document.get("document_name", String.class),
                document.get(DOCUMENT.STATUS), document.get(DOCUMENT.PARSE_STATUS), chunks));
    }

    public Optional<SourceSearchAuthorityDocument> findDocumentByChunk(long workspaceId,
                                                                        long sourceChunkId) {
        Integer documentId = dsl.select(SOURCE_CHUNK.DOCUMENT_ID)
                .from(SOURCE_CHUNK)
                .join(DOCUMENT).on(DOCUMENT.ID.eq(SOURCE_CHUNK.DOCUMENT_ID))
                .where(DOCUMENT.WORKSPACE_ID.eq(Math.toIntExact(workspaceId)))
                .and(SOURCE_CHUNK.ID.eq(Math.toIntExact(sourceChunkId)))
                .fetchOne(SOURCE_CHUNK.DOCUMENT_ID);
        return documentId == null ? Optional.empty() : findDocument(workspaceId, documentId.longValue());
    }
}
