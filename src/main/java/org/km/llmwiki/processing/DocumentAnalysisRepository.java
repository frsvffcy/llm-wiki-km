package org.km.llmwiki.processing;

import org.jooq.DSLContext;
import org.km.llmwiki.ai.DocumentAnalysisPrompt;
import org.km.llmwiki.ai.LlmAnalysisResult;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

import static org.km.llmwiki.persistence.jooq.generated.Tables.DOCUMENT_ANALYSIS;

@Repository
public class DocumentAnalysisRepository {

    private final DSLContext dsl;

    public DocumentAnalysisRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public long saveSuccess(long jobItemId, long documentId, DocumentAnalysisPrompt prompt,
                            LlmAnalysisResult result, String resultJson) {
        return save(jobItemId, documentId, DocumentAnalysisStatus.SUCCEEDED, prompt, result, resultJson, null, null);
    }

    public void saveFailure(long jobItemId, long documentId, DocumentAnalysisPrompt prompt,
                            String errorCode, String errorMessage) {
        save(jobItemId, documentId, DocumentAnalysisStatus.FAILED, prompt, null, null, errorCode, errorMessage);
    }

    private long save(long jobItemId, long documentId, DocumentAnalysisStatus status, DocumentAnalysisPrompt prompt,
                      LlmAnalysisResult result, String resultJson, String errorCode, String errorMessage) {
        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        Integer id = dsl.insertInto(DOCUMENT_ANALYSIS)
                .columns(
                        DOCUMENT_ANALYSIS.JOB_ITEM_ID,
                        DOCUMENT_ANALYSIS.DOCUMENT_ID,
                        DOCUMENT_ANALYSIS.STATUS,
                        DOCUMENT_ANALYSIS.PROMPT_IDENTIFIER,
                        DOCUMENT_ANALYSIS.PROMPT_VERSION,
                        DOCUMENT_ANALYSIS.PROMPT_CONTENT_HASH,
                        DOCUMENT_ANALYSIS.PROVIDER,
                        DOCUMENT_ANALYSIS.MODEL,
                        DOCUMENT_ANALYSIS.CONTRACT_VERSION,
                        DOCUMENT_ANALYSIS.RESULT_JSON,
                        DOCUMENT_ANALYSIS.ERROR_CODE,
                        DOCUMENT_ANALYSIS.ERROR_MESSAGE,
                        DOCUMENT_ANALYSIS.CREATED_AT,
                        DOCUMENT_ANALYSIS.UPDATED_AT
                )
                .values(
                        (int) jobItemId,
                        (int) documentId,
                        status.name(),
                        prompt == null ? null : prompt.identifier(),
                        prompt == null ? null : prompt.version(),
                        prompt == null ? null : prompt.contentHash(),
                        result == null ? null : result.metadata().provider(),
                        result == null ? null : result.metadata().model(),
                        result == null ? null : result.metadata().contractVersion(),
                        resultJson,
                        errorCode,
                        errorMessage,
                        now,
                        now
                )
                .returningResult(DOCUMENT_ANALYSIS.ID)
                .fetchOne(DOCUMENT_ANALYSIS.ID);

        if (id == null) {
            throw new IllegalStateException("Document analysis insert did not return a generated id");
        }
        return id.longValue();
    }
}
