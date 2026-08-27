package org.km.llmwiki.processing;

import org.km.llmwiki.ai.DocumentAnalysisPrompt;
import org.km.llmwiki.ai.LlmAnalysisResult;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

@Repository
public class DocumentAnalysisRepository {

    private final JdbcClient jdbcClient;

    public DocumentAnalysisRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
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
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcClient.sql("""
                        INSERT INTO document_analysis (
                            job_item_id, document_id, status, prompt_identifier, prompt_version, prompt_content_hash,
                            provider, model, contract_version, result_json, error_code, error_message, created_at, updated_at)
                        VALUES (
                            :jobItemId, :documentId, :status, :promptIdentifier, :promptVersion, :promptContentHash,
                            :provider, :model, :contractVersion, :resultJson, :errorCode, :errorMessage, :now, :now)
                        """).paramSource(new MapSqlParameterSource()
                        .addValue("jobItemId", jobItemId).addValue("documentId", documentId).addValue("status", status.name())
                        .addValue("promptIdentifier", prompt == null ? null : prompt.identifier())
                        .addValue("promptVersion", prompt == null ? null : prompt.version())
                        .addValue("promptContentHash", prompt == null ? null : prompt.contentHash())
                        .addValue("provider", result == null ? null : result.metadata().provider())
                        .addValue("model", result == null ? null : result.metadata().model())
                        .addValue("contractVersion", result == null ? null : result.metadata().contractVersion())
                        .addValue("resultJson", resultJson).addValue("errorCode", errorCode)
                        .addValue("errorMessage", errorMessage).addValue("now", now))
                .update(keyHolder);
        Number id = keyHolder.getKey();
        if (id == null) {
            throw new IllegalStateException("Document analysis insert did not return a generated id");
        }
        return id.longValue();
    }
}
