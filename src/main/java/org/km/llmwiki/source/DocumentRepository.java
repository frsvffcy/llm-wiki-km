package org.km.llmwiki.source;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.SortField;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.val;
import static org.km.llmwiki.persistence.jooq.generated.Tables.DOCUMENT;
import static org.km.llmwiki.persistence.jooq.generated.Tables.SOURCE_CHUNK;

@Repository
public class DocumentRepository {

    private final DSLContext dsl;

    public DocumentRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public long insert(long workspaceId, String fileName, String originalFileName, String extension,
                       String sourcePath, String sha256, Long fileSize, String mimeType, String createdAt,
                       String status, Long duplicateOfDocumentId, Long parentVersionDocumentId) {
        Integer id = dsl.insertInto(DOCUMENT)
                .columns(
                        DOCUMENT.WORKSPACE_ID,
                        DOCUMENT.FILE_NAME,
                        DOCUMENT.ORIGINAL_FILE_NAME,
                        DOCUMENT.EXTENSION,
                        DOCUMENT.SOURCE_PATH,
                        DOCUMENT.SHA256,
                        DOCUMENT.FILE_SIZE,
                        DOCUMENT.MIME_TYPE,
                        DOCUMENT.STATUS,
                        DOCUMENT.DUPLICATE_OF_DOCUMENT_ID,
                        DOCUMENT.PARENT_VERSION_DOCUMENT_ID,
                        DOCUMENT.CREATED_AT,
                        DOCUMENT.UPDATED_AT
                )
                .values(
                        (int) workspaceId,
                        fileName,
                        originalFileName,
                        extension,
                        sourcePath,
                        sha256,
                        fileSize == null ? null : fileSize.intValue(),
                        mimeType,
                        status,
                        duplicateOfDocumentId == null ? null : duplicateOfDocumentId.intValue(),
                        parentVersionDocumentId == null ? null : parentVersionDocumentId.intValue(),
                        createdAt,
                        createdAt
                )
                .returningResult(DOCUMENT.ID)
                .fetchOne(DOCUMENT.ID);

        if (id == null) {
            throw new IllegalStateException("Document insert did not return a generated id");
        }
        return id.longValue();
    }

    public void deleteById(long id) {
        dsl.deleteFrom(DOCUMENT).where(DOCUMENT.ID.eq((int) id)).execute();
    }

    public Optional<DocumentSummary> findActiveByWorkspaceAndSourcePath(long workspaceId, String sourcePath) {
        return dsl.select(DOCUMENT.ID, DOCUMENT.SOURCE_PATH, DOCUMENT.SHA256)
                .from(DOCUMENT)
                .where(DOCUMENT.WORKSPACE_ID.eq((int) workspaceId))
                .and(DOCUMENT.SOURCE_PATH.eq(sourcePath))
                .and(DOCUMENT.STATUS.ne("DELETED"))
                .and(DOCUMENT.STATUS.ne("SUPERSEDED"))
                .orderBy(DOCUMENT.ID.desc())
                .limit(1)
                .fetchOptional(r -> new DocumentSummary(
                        r.get(DOCUMENT.ID).longValue(),
                        r.get(DOCUMENT.SOURCE_PATH),
                        r.get(DOCUMENT.SHA256)));
    }

    public Optional<DocumentExtractionTarget> findExtractionTarget(long workspaceId, long documentId) {
        return dsl.select(
                        DOCUMENT.ID,
                        DOCUMENT.FILE_NAME,
                        DOCUMENT.MIME_TYPE,
                        DOCUMENT.SOURCE_PATH,
                        DOCUMENT.PARSE_STATUS
                )
                .from(DOCUMENT)
                .where(DOCUMENT.WORKSPACE_ID.eq((int) workspaceId))
                .and(DOCUMENT.ID.eq((int) documentId))
                .and(DOCUMENT.STATUS.notIn("DELETED", "SUPERSEDED"))
                .fetchOptional(r -> new DocumentExtractionTarget(
                        r.get(DOCUMENT.ID).longValue(),
                        r.get(DOCUMENT.FILE_NAME),
                        r.get(DOCUMENT.MIME_TYPE),
                        r.get(DOCUMENT.SOURCE_PATH),
                        r.get(DOCUMENT.PARSE_STATUS)));
    }

    public List<DocumentAnalysisTarget> findAnalysisTargets(long workspaceId) {
        return dsl.select(
                        DOCUMENT.ID,
                        coalesce(DOCUMENT.ORIGINAL_FILE_NAME, DOCUMENT.FILE_NAME).as("original_file_name"),
                        coalesce(DOCUMENT.MIME_TYPE, "application/octet-stream").as("mime_type"),
                        DOCUMENT.EXTRACTED_TEXT_HASH
                )
                .from(DOCUMENT)
                .where(DOCUMENT.WORKSPACE_ID.eq((int) workspaceId))
                .and(DOCUMENT.STATUS.notIn("DELETED", "SUPERSEDED"))
                .and(DOCUMENT.PARSE_STATUS.eq(DocumentStatus.PROCESSED.name()))
                .and(DOCUMENT.EXTRACTED_TEXT_HASH.isNotNull())
                .and(exists(
                        selectOne()
                                .from(SOURCE_CHUNK)
                                .where(SOURCE_CHUNK.DOCUMENT_ID.eq(DOCUMENT.ID))
                ))
                .orderBy(DOCUMENT.ID.asc())
                .fetch(r -> new DocumentAnalysisTarget(
                        r.get(DOCUMENT.ID).longValue(),
                        r.get("original_file_name", String.class),
                        r.get("mime_type", String.class),
                        r.get(DOCUMENT.EXTRACTED_TEXT_HASH)));
    }

    public void markExtractionSucceeded(long documentId, String extractedTextHash) {
        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        dsl.update(DOCUMENT)
                .set(DOCUMENT.PARSE_STATUS, DocumentStatus.PROCESSED.name())
                .set(DOCUMENT.EXTRACTED_TEXT_HASH, extractedTextHash)
                .setNull(DOCUMENT.ERROR_CODE)
                .setNull(DOCUMENT.ERROR_MESSAGE)
                .set(DOCUMENT.UPDATED_AT, now)
                .where(DOCUMENT.ID.eq((int) documentId))
                .execute();
    }

    public void markExtractionFailed(long documentId, DocumentStatus parseStatus,
                                     String errorCode, String errorMessage) {
        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        dsl.update(DOCUMENT)
                .set(DOCUMENT.PARSE_STATUS, parseStatus.name())
                .setNull(DOCUMENT.EXTRACTED_TEXT_HASH)
                .set(DOCUMENT.ERROR_CODE, errorCode)
                .set(DOCUMENT.ERROR_MESSAGE, errorMessage)
                .set(DOCUMENT.UPDATED_AT, now)
                .where(DOCUMENT.ID.eq((int) documentId))
                .execute();
    }

    public Optional<DocumentSummary> findActiveByWorkspaceAndSha256(long workspaceId, String sha256) {
        return dsl.select(DOCUMENT.ID, DOCUMENT.SOURCE_PATH, DOCUMENT.SHA256)
                .from(DOCUMENT)
                .where(DOCUMENT.WORKSPACE_ID.eq((int) workspaceId))
                .and(DOCUMENT.SHA256.eq(sha256))
                .and(DOCUMENT.STATUS.ne("DELETED"))
                .and(DOCUMENT.STATUS.ne("SUPERSEDED"))
                .orderBy(DOCUMENT.ID.asc())
                .limit(1)
                .fetchOptional(r -> new DocumentSummary(
                        r.get(DOCUMENT.ID).longValue(),
                        r.get(DOCUMENT.SOURCE_PATH),
                        r.get(DOCUMENT.SHA256)));
    }

    public List<DocumentSummary> findInboxManaged(long workspaceId) {
        return dsl.select(DOCUMENT.ID, DOCUMENT.SOURCE_PATH, DOCUMENT.SHA256)
                .from(DOCUMENT)
                .where(DOCUMENT.WORKSPACE_ID.eq((int) workspaceId))
                .and(DOCUMENT.SOURCE_PATH.like("inbox/%"))
                .and(DOCUMENT.STATUS.notIn("DELETED", "SUPERSEDED", "ARCHIVED"))
                .fetch(r -> new DocumentSummary(
                        r.get(DOCUMENT.ID).longValue(),
                        r.get(DOCUMENT.SOURCE_PATH),
                        r.get(DOCUMENT.SHA256)));
    }

    public void markDeleted(long id) {
        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        dsl.update(DOCUMENT)
                .set(DOCUMENT.STATUS, "DELETED")
                .set(DOCUMENT.UPDATED_AT, now)
                .where(DOCUMENT.ID.eq((int) id))
                .execute();
    }

    public void markSuperseded(long id) {
        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        dsl.update(DOCUMENT)
                .set(DOCUMENT.STATUS, "SUPERSEDED")
                .set(DOCUMENT.UPDATED_AT, now)
                .where(DOCUMENT.ID.eq((int) id))
                .execute();
    }

    public long countInboxDocuments(long workspaceId, String statusFilter, String extensionFilter) {
        Condition where = inboxBaseCondition(workspaceId, statusFilter, extensionFilter);
        Integer count = dsl.selectCount()
                .from(DOCUMENT)
                .where(where)
                .fetchOne(0, Integer.class);
        return count == null ? 0 : count.longValue();
    }

    public List<InboxDocumentRow> findInboxDocuments(long workspaceId, String statusFilter,
                                                     String extensionFilter, String orderBy, int limit, int offset) {
        Condition where = inboxBaseCondition(workspaceId, statusFilter, extensionFilter);
        var step = dsl.select(
                        DOCUMENT.ID,
                        DOCUMENT.FILE_NAME,
                        coalesce(DOCUMENT.ORIGINAL_FILE_NAME, DOCUMENT.FILE_NAME).as("original_file_name"),
                        DOCUMENT.EXTENSION,
                        DOCUMENT.MIME_TYPE,
                        DOCUMENT.FILE_SIZE,
                        DOCUMENT.STATUS,
                        DOCUMENT.PARSE_STATUS,
                        DOCUMENT.ERROR_CODE,
                        DOCUMENT.ERROR_MESSAGE,
                        DOCUMENT.CREATED_AT
                )
                .from(DOCUMENT)
                .where(where);

        var ordered = (orderBy != null && !orderBy.isBlank())
                ? step.orderBy(parseSortField(orderBy))
                : step.orderBy(DOCUMENT.CREATED_AT.desc());

        return ordered.limit(limit).offset(offset).fetch(r -> new InboxDocumentRow(
                r.get(DOCUMENT.ID).longValue(),
                r.get(DOCUMENT.FILE_NAME),
                r.get("original_file_name", String.class),
                r.get(DOCUMENT.EXTENSION),
                r.get(DOCUMENT.MIME_TYPE),
                r.get(DOCUMENT.FILE_SIZE) == null ? null : r.get(DOCUMENT.FILE_SIZE).longValue(),
                r.get(DOCUMENT.STATUS),
                r.get(DOCUMENT.PARSE_STATUS),
                r.get(DOCUMENT.ERROR_CODE),
                r.get(DOCUMENT.ERROR_MESSAGE),
                r.get(DOCUMENT.CREATED_AT)));
    }

    private static org.jooq.SortField<?> parseSortField(String orderBy) {
        // orderBy format: "column_name ASC" or "column_name DESC"
        String[] parts = orderBy.trim().split("\\s+", 2);
        var field = org.jooq.impl.DSL.field(org.jooq.impl.DSL.name(parts[0]));
        if (parts.length > 1 && parts[1].equalsIgnoreCase("DESC")) {
            return field.desc();
        }
        return field.asc();
    }

    public Optional<InboxDocumentRow> findInboxDocument(long workspaceId, long documentId) {
        return dsl.select(
                        DOCUMENT.ID,
                        DOCUMENT.FILE_NAME,
                        coalesce(DOCUMENT.ORIGINAL_FILE_NAME, DOCUMENT.FILE_NAME).as("original_file_name"),
                        DOCUMENT.EXTENSION,
                        DOCUMENT.MIME_TYPE,
                        DOCUMENT.FILE_SIZE,
                        DOCUMENT.STATUS,
                        DOCUMENT.PARSE_STATUS,
                        DOCUMENT.ERROR_CODE,
                        DOCUMENT.ERROR_MESSAGE,
                        DOCUMENT.CREATED_AT
                )
                .from(DOCUMENT)
                .where(DOCUMENT.WORKSPACE_ID.eq((int) workspaceId))
                .and(DOCUMENT.ID.eq((int) documentId))
                .and(DOCUMENT.SOURCE_PATH.like("inbox/%"))
                .and(DOCUMENT.STATUS.notIn("DELETED", "SUPERSEDED", "ARCHIVED"))
                .fetchOptional(r -> new InboxDocumentRow(
                        r.get(DOCUMENT.ID).longValue(),
                        r.get(DOCUMENT.FILE_NAME),
                        r.get("original_file_name", String.class),
                        r.get(DOCUMENT.EXTENSION),
                        r.get(DOCUMENT.MIME_TYPE),
                        r.get(DOCUMENT.FILE_SIZE) == null ? null : r.get(DOCUMENT.FILE_SIZE).longValue(),
                        r.get(DOCUMENT.STATUS),
                        r.get(DOCUMENT.PARSE_STATUS),
                        r.get(DOCUMENT.ERROR_CODE),
                        r.get(DOCUMENT.ERROR_MESSAGE),
                        r.get(DOCUMENT.CREATED_AT)));
    }

    public Optional<DocumentDeletionView> findDeletionView(long workspaceId, long documentId) {
        return dsl.select(DOCUMENT.ID, DOCUMENT.SOURCE_PATH, DOCUMENT.STATUS)
                .from(DOCUMENT)
                .where(DOCUMENT.WORKSPACE_ID.eq((int) workspaceId))
                .and(DOCUMENT.ID.eq((int) documentId))
                .fetchOptional(r -> new DocumentDeletionView(
                        r.get(DOCUMENT.ID).longValue(),
                        r.get(DOCUMENT.SOURCE_PATH),
                        r.get(DOCUMENT.STATUS)));
    }

    public List<DocumentSummary> findCurrentDuplicatesOf(long workspaceId, long canonicalDocumentId) {
        return dsl.select(DOCUMENT.ID, DOCUMENT.SOURCE_PATH, DOCUMENT.SHA256)
                .from(DOCUMENT)
                .where(DOCUMENT.WORKSPACE_ID.eq((int) workspaceId))
                .and(DOCUMENT.DUPLICATE_OF_DOCUMENT_ID.eq((int) canonicalDocumentId))
                .and(DOCUMENT.STATUS.eq("DUPLICATE"))
                .orderBy(DOCUMENT.ID.asc())
                .fetch(r -> new DocumentSummary(
                        r.get(DOCUMENT.ID).longValue(),
                        r.get(DOCUMENT.SOURCE_PATH),
                        r.get(DOCUMENT.SHA256)));
    }

    public void promoteDuplicateToCanonical(long duplicateDocumentId) {
        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        dsl.update(DOCUMENT)
                .set(DOCUMENT.STATUS, "PENDING")
                .setNull(DOCUMENT.DUPLICATE_OF_DOCUMENT_ID)
                .set(DOCUMENT.UPDATED_AT, now)
                .where(DOCUMENT.ID.eq((int) duplicateDocumentId))
                .execute();
    }

    public void repointDuplicates(long previousCanonicalId, long newCanonicalId) {
        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        dsl.update(DOCUMENT)
                .set(DOCUMENT.DUPLICATE_OF_DOCUMENT_ID, (int) newCanonicalId)
                .set(DOCUMENT.UPDATED_AT, now)
                .where(DOCUMENT.DUPLICATE_OF_DOCUMENT_ID.eq((int) previousCanonicalId))
                .and(DOCUMENT.STATUS.eq("DUPLICATE"))
                .execute();
    }

    private static Condition inboxBaseCondition(long workspaceId, String statusFilter, String extensionFilter) {
        Condition condition = DOCUMENT.WORKSPACE_ID.eq((int) workspaceId)
                .and(DOCUMENT.SOURCE_PATH.like("inbox/%"))
                .and(DOCUMENT.STATUS.notIn("DELETED", "SUPERSEDED", "ARCHIVED"));
        if (statusFilter != null) {
            condition = condition.and(DOCUMENT.STATUS.eq(statusFilter));
        }
        if (extensionFilter != null) {
            condition = condition.and(DOCUMENT.EXTENSION.eq(extensionFilter));
        }
        return condition;
    }
}
