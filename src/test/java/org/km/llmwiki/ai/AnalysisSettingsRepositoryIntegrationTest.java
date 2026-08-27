package org.km.llmwiki.ai;

import org.junit.jupiter.api.Test;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "app.persistence.sqlite.path=target/test-data/analysis-settings-${random.uuid}/knowledge.db")
class AnalysisSettingsRepositoryIntegrationTest extends IsolatedIntegrationTest {

    @Autowired
    private AnalysisSettingsRepository repository;

    @Test
    void prefersWorkspaceNonSensitiveSettingsWithoutReadingSecretRows() {
        long workspaceId = insertWorkspace("/tmp/analysis-settings");
        insertSetting(null, "llm", "provider", "global-provider");
        insertSetting(null, "llm", "model", "global-model");
        insertSetting(null, "llm", "api_key", "must-not-be-read");
        insertSetting(workspaceId, "llm", "provider", "workspace-provider");
        insertSetting(workspaceId, "analysis", "maximum_evidence_chunks", "12");

        AnalysisSettings settings = repository.resolve(workspaceId);

        assertThat(settings).isEqualTo(new AnalysisSettings("workspace-provider", "global-model", 12));
    }

    @Test
    void fallsBackToOfflineDefaultsWhenNoAllowedSettingExists() {
        long workspaceId = insertWorkspace("/tmp/analysis-defaults");
        insertSetting(workspaceId, "llm", "token", "must-not-be-read");

        assertThat(repository.resolve(workspaceId)).isEqualTo(new AnalysisSettings("stub", "offline", 50));
    }

    private long insertWorkspace(String rootPath) {
        db().sql("""
                        INSERT INTO workspace (name, root_path, inbox_path, archive_path, vault_path,
                            data_path, config_path, status, created_at, updated_at)
                        VALUES ('analysis-test', :rootPath, 'inbox', 'archive', 'vault', 'data', 'config',
                            'ACTIVE', :now, :now)
                        """)
                .param("rootPath", rootPath)
                .param("now", Instant.now().toString())
                .update();
        return db().sql("SELECT id FROM workspace WHERE root_path = :rootPath")
                .param("rootPath", rootPath)
                .query(Long.class)
                .single();
    }

    private void insertSetting(Long workspaceId, String group, String key, String value) {
        db().sql("""
                        INSERT INTO setting (workspace_id, setting_group, setting_key, setting_value,
                            value_type, created_at, updated_at)
                        VALUES (:workspaceId, :group, :key, :value, 'STRING', :now, :now)
                        """)
                .param("workspaceId", workspaceId)
                .param("group", group)
                .param("key", key)
                .param("value", value)
                .param("now", Instant.now().toString())
                .update();
    }
}
