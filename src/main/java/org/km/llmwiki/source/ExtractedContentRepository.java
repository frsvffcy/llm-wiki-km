package org.km.llmwiki.source;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import static org.jooq.impl.DSL.excluded;
import static org.km.llmwiki.persistence.jooq.generated.Tables.DOCUMENT_EXTRACTED_CONTENT;

@Repository
public class ExtractedContentRepository {

    private final DSLContext dsl;

    public ExtractedContentRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void save(long documentId, String content, int chunkCount) {
        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        dsl.insertInto(DOCUMENT_EXTRACTED_CONTENT)
                .columns(
                        DOCUMENT_EXTRACTED_CONTENT.DOCUMENT_ID,
                        DOCUMENT_EXTRACTED_CONTENT.CONTENT,
                        DOCUMENT_EXTRACTED_CONTENT.CHUNK_COUNT,
                        DOCUMENT_EXTRACTED_CONTENT.CREATED_AT,
                        DOCUMENT_EXTRACTED_CONTENT.UPDATED_AT
                )
                .values(
                        (int) documentId,
                        content,
                        chunkCount,
                        now,
                        now
                )
                .onConflict(DOCUMENT_EXTRACTED_CONTENT.DOCUMENT_ID)
                .doUpdate()
                .set(DOCUMENT_EXTRACTED_CONTENT.CONTENT, excluded(DOCUMENT_EXTRACTED_CONTENT.CONTENT))
                .set(DOCUMENT_EXTRACTED_CONTENT.CHUNK_COUNT, excluded(DOCUMENT_EXTRACTED_CONTENT.CHUNK_COUNT))
                .set(DOCUMENT_EXTRACTED_CONTENT.UPDATED_AT, excluded(DOCUMENT_EXTRACTED_CONTENT.UPDATED_AT))
                .execute();
    }

    public Optional<ExtractedContentRecord> findByDocumentId(long documentId) {
        return dsl.selectFrom(DOCUMENT_EXTRACTED_CONTENT)
                .where(DOCUMENT_EXTRACTED_CONTENT.DOCUMENT_ID.eq((int) documentId))
                .fetchOptional(r -> new ExtractedContentRecord(
                        r.getDocumentId().longValue(),
                        r.getContent(),
                        r.getChunkCount()
                ));
    }

    public void deleteByDocumentId(long documentId) {
        dsl.deleteFrom(DOCUMENT_EXTRACTED_CONTENT)
                .where(DOCUMENT_EXTRACTED_CONTENT.DOCUMENT_ID.eq((int) documentId))
                .execute();
    }
}
