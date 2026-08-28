package org.km.llmwiki.source;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import static org.km.llmwiki.persistence.jooq.generated.Tables.DOCUMENT;
import static org.km.llmwiki.persistence.jooq.generated.Tables.KNOWLEDGE_CANDIDATE;
import static org.km.llmwiki.persistence.jooq.generated.Tables.SOURCE_CHUNK;

@Repository
public class SourceChunkRepository {

    private final DSLContext dsl;

    public SourceChunkRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void replaceForDocument(long documentId, List<SourceChunkDraft> chunks) {
        dsl.deleteFrom(KNOWLEDGE_CANDIDATE)
                .where(KNOWLEDGE_CANDIDATE.DOCUMENT_ID.eq((int) documentId))
                .execute();

        deleteByDocumentId(documentId);
        if (chunks.isEmpty()) {
            return;
        }

        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        for (SourceChunkDraft chunk : chunks) {
            dsl.insertInto(SOURCE_CHUNK)
                    .columns(
                            SOURCE_CHUNK.DOCUMENT_ID,
                            SOURCE_CHUNK.CHUNK_NO,
                            SOURCE_CHUNK.PAGE_NO,
                            SOURCE_CHUNK.SECTION,
                            SOURCE_CHUNK.HEADING_PATH,
                            SOURCE_CHUNK.CONTENT,
                            SOURCE_CHUNK.NORMALIZED_CONTENT,
                            SOURCE_CHUNK.CONTENT_HASH,
                            SOURCE_CHUNK.CREATED_AT,
                            SOURCE_CHUNK.UPDATED_AT
                    )
                    .values(
                            (int) documentId,
                            chunk.chunkNo(),
                            chunk.pageNo(),
                            chunk.section(),
                            chunk.headingPath(),
                            chunk.content(),
                            chunk.normalizedContent(),
                            chunk.contentHash(),
                            now,
                            now
                    )
                    .execute();
        }
    }

    public List<SourceChunk> findByDocumentId(long documentId) {
        return dsl.selectFrom(SOURCE_CHUNK)
                .where(SOURCE_CHUNK.DOCUMENT_ID.eq((int) documentId))
                .orderBy(SOURCE_CHUNK.CHUNK_NO.asc())
                .fetch(r -> new SourceChunk(
                        r.getId().longValue(),
                        r.getDocumentId().longValue(),
                        r.getChunkNo(),
                        r.getPageNo(),
                        r.getSection(),
                        r.getHeadingPath(),
                        r.getContent(),
                        r.getNormalizedContent(),
                        r.getContentHash()
                ));
    }

    public Optional<SourceChunk> findByIdAndWorkspaceId(long chunkId, long workspaceId) {
        return dsl.select(
                        SOURCE_CHUNK.ID,
                        SOURCE_CHUNK.DOCUMENT_ID,
                        SOURCE_CHUNK.CHUNK_NO,
                        SOURCE_CHUNK.PAGE_NO,
                        SOURCE_CHUNK.SECTION,
                        SOURCE_CHUNK.HEADING_PATH,
                        SOURCE_CHUNK.CONTENT,
                        SOURCE_CHUNK.NORMALIZED_CONTENT,
                        SOURCE_CHUNK.CONTENT_HASH
                )
                .from(SOURCE_CHUNK)
                .join(DOCUMENT).on(DOCUMENT.ID.eq(SOURCE_CHUNK.DOCUMENT_ID))
                .where(SOURCE_CHUNK.ID.eq((int) chunkId))
                .and(DOCUMENT.WORKSPACE_ID.eq((int) workspaceId))
                .and(DOCUMENT.STATUS.notIn("DELETED", "SUPERSEDED"))
                .fetchOptional(r -> new SourceChunk(
                        r.get(SOURCE_CHUNK.ID).longValue(),
                        r.get(SOURCE_CHUNK.DOCUMENT_ID).longValue(),
                        r.get(SOURCE_CHUNK.CHUNK_NO),
                        r.get(SOURCE_CHUNK.PAGE_NO),
                        r.get(SOURCE_CHUNK.SECTION),
                        r.get(SOURCE_CHUNK.HEADING_PATH),
                        r.get(SOURCE_CHUNK.CONTENT),
                        r.get(SOURCE_CHUNK.NORMALIZED_CONTENT),
                        r.get(SOURCE_CHUNK.CONTENT_HASH)
                ));
    }

    public void deleteByDocumentId(long documentId) {
        dsl.deleteFrom(SOURCE_CHUNK)
                .where(SOURCE_CHUNK.DOCUMENT_ID.eq((int) documentId))
                .execute();
    }
}
