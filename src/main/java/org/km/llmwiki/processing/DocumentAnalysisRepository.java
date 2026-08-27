package org.km.llmwiki.processing;

import org.km.llmwiki.ai.DocumentAnalysisPrompt;
import org.km.llmwiki.ai.LlmAnalysisResult;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

@Repository
public class DocumentAnalysisRepository {

    private final JdbcClient jdbcClient;

    public DocumentAnalysisRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void saveSuccess(long jobItemId, long documentId, DocumentAnalysisPrompt prompt,
                            LlmAnalysisResult result, String resultJson) {
        save(jobItemId, documentId, DocumentAnalysisStatus.SUCCEEDED, prompt, result, resultJson, null, null);
    }

    public void saveFailure(long jobItemId, long documentId, DocumentAnalysisPrompt prompt,
                            String errorCode, String errorMessage) {
        save(jobItemId, documentId, DocumentAnalysisStatus.FAILED, prompt, null, null, errorCode, errorMessage);
    }

    private void save(long jobItemId, long documentId, DocumentAnalysisStatus status, DocumentAnalysisPrompt prompt,
                      LlmAnalysisResult result, String resultJson, String errorCode, String errorMessage) {
        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        jdbcClient.sql("""
                        INSERT INTO document_analysis (
                            job_item_id, document_id, status, prompt_identifier, prompt_version, prompt_content_hash,
                            provider, model, contract_version, result_json, error_code, error_message, created_at, updated_at)
                        VALUES (
                            :jobItemId, :documentId, :status, :promptIdentifier, :promptVersion, :promptContentHash,
                            :provider, :model, :contractVersion, :resultJson, :errorCode, :errorMessage, :now, :now)
                        """).param("jobItemId", jobItemId).param("documentId", documentId).param("status", status.name())
                .param("promptIdentifier", prompt == null ? null : prompt.identifier())
                .param("promptVersion", prompt == null ? null : prompt.version())
                .param("promptContentHash", prompt == null ? null : prompt.contentHash())
                .param("provider", result == null ? null : result.metadata().provider())
                .param("model", result == null ? null : result.metadata().model())
                .param("contractVersion", result == null ? null : result.metadata().contractVersion())
                .param("resultJson", resultJson).param("errorCode", errorCode).param("errorMessage", errorMessage)
                .param("now", now).update();
    }
}
