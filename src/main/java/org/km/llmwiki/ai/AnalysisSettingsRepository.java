package org.km.llmwiki.ai;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads only allow-listed, non-secret analysis settings. Workspace values take precedence over
 * global values, and absent values fall back to safe offline defaults.
 */
@Repository
public class AnalysisSettingsRepository {

    private static final String LLM_PROVIDER = "llm.provider";
    private static final String LLM_MODEL = "llm.model";
    private static final String MAXIMUM_EVIDENCE_CHUNKS = "analysis.maximum_evidence_chunks";

    private final JdbcClient jdbcClient;

    public AnalysisSettingsRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public AnalysisSettings resolve(long workspaceId) {
        Map<String, String> global = new HashMap<>();
        Map<String, String> workspace = new HashMap<>();
        List<SettingRow> rows = jdbcClient.sql("""
                        SELECT workspace_id, setting_group, setting_key, setting_value
                        FROM setting
                        WHERE (workspace_id IS NULL OR workspace_id = :workspaceId)
                          AND (
                              (setting_group = 'llm' AND setting_key IN ('provider', 'model'))
                              OR (setting_group = 'analysis' AND setting_key = 'maximum_evidence_chunks')
                          )
                        """)
                .param("workspaceId", workspaceId)
                .query((resultSet, rowNum) -> {
                    Object workspaceValue = resultSet.getObject("workspace_id");
                    Long rowWorkspaceId = workspaceValue == null ? null : ((Number) workspaceValue).longValue();
                    return new SettingRow(rowWorkspaceId, resultSet.getString("setting_group"),
                            resultSet.getString("setting_key"), resultSet.getString("setting_value"));
                })
                .list();
        for (SettingRow row : rows) {
            String name = row.group() + "." + row.key();
            if (!isAllowed(name)) {
                continue;
            }
            (row.workspaceId() == null ? global : workspace).put(name, row.value());
        }

        String provider = choose(workspace, global, LLM_PROVIDER, AnalysisSettings.DEFAULT_PROVIDER);
        String model = choose(workspace, global, LLM_MODEL, AnalysisSettings.DEFAULT_MODEL);
        String maximum = choose(workspace, global, MAXIMUM_EVIDENCE_CHUNKS,
                Integer.toString(AnalysisSettings.DEFAULT_MAXIMUM_EVIDENCE_CHUNKS));
        try {
            return new AnalysisSettings(provider, model, Integer.parseInt(maximum));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new PromptLoadException(PromptLoadErrorCode.ANALYSIS_SETTING_INVALID,
                    "Document analysis settings are invalid", exception);
        }
    }

    private static boolean isAllowed(String name) {
        return LLM_PROVIDER.equals(name) || LLM_MODEL.equals(name) || MAXIMUM_EVIDENCE_CHUNKS.equals(name);
    }

    private static String choose(Map<String, String> workspace, Map<String, String> global,
                                 String key, String fallback) {
        if (workspace.containsKey(key)) {
            return workspace.get(key);
        }
        if (global.containsKey(key)) {
            return global.get(key);
        }
        return fallback;
    }

    private record SettingRow(Long workspaceId, String group, String key, String value) {
    }
}
