package org.km.llmwiki.ai;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.km.llmwiki.persistence.jooq.generated.Tables.SETTING;

/**
 * Reads only allow-listed, non-secret analysis settings. Workspace values take precedence over
 * global values, and absent values fall back to safe offline defaults.
 */
@Repository
public class AnalysisSettingsRepository {

    private static final String LLM_PROVIDER = "llm.provider";
    private static final String LLM_MODEL = "llm.model";
    private static final String MAXIMUM_EVIDENCE_CHUNKS = "analysis.maximum_evidence_chunks";

    private final DSLContext dsl;

    public AnalysisSettingsRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public AnalysisSettings resolve(long workspaceId) {
        Map<String, String> global = new HashMap<>();
        Map<String, String> workspace = new HashMap<>();

        List<SettingRow> rows = dsl.select(
                        SETTING.WORKSPACE_ID,
                        SETTING.SETTING_GROUP,
                        SETTING.SETTING_KEY,
                        SETTING.SETTING_VALUE
                )
                .from(SETTING)
                .where(SETTING.WORKSPACE_ID.isNull().or(SETTING.WORKSPACE_ID.eq((int) workspaceId)))
                .and(
                        (SETTING.SETTING_GROUP.eq("llm").and(SETTING.SETTING_KEY.in("provider", "model")))
                                .or(SETTING.SETTING_GROUP.eq("analysis").and(SETTING.SETTING_KEY.eq("maximum_evidence_chunks")))
                )
                .fetch(r -> new SettingRow(
                        r.get(SETTING.WORKSPACE_ID) == null ? null : r.get(SETTING.WORKSPACE_ID).longValue(),
                        r.get(SETTING.SETTING_GROUP),
                        r.get(SETTING.SETTING_KEY),
                        r.get(SETTING.SETTING_VALUE)
                ));

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
