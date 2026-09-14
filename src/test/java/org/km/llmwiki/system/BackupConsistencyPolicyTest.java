package org.km.llmwiki.system;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class BackupConsistencyPolicyTest {

    private static BackupConsistencyPolicy.BackupManifest complete() {
        return new BackupConsistencyPolicy.BackupManifest(
                true, true, true, true, true, true, false);
    }

    private static Map<String, Long> completeSizes() {
        return Map.of(
                PersistentStateClassifier.VAULT, 128L,
                PersistentStateClassifier.ARCHIVE, 128L,
                PersistentStateClassifier.KNOWLEDGE_DB, 4096L,
                PersistentStateClassifier.DEPLOYMENT_CONFIG, 64L);
    }

    @Test
    void completeAuthoritativeSetValidatesForBackup() {
        BackupConsistencyPolicy.BackupValidation validation =
                BackupConsistencyPolicy.validateForBackup(complete());

        assertThat(validation.valid()).isTrue();
    }

    @Test
    void databaseOnlyBackupIsNeverComplete() {
        BackupConsistencyPolicy.BackupManifest databaseOnly =
                new BackupConsistencyPolicy.BackupManifest(
                        false, false, true, false, true, true, false);

        assertThat(BackupConsistencyPolicy.validateForBackup(databaseOnly).valid()).isFalse();
    }

    @Test
    void canonicalFilesOnlyBackupIsNeverComplete() {
        BackupConsistencyPolicy.BackupManifest filesOnly =
                new BackupConsistencyPolicy.BackupManifest(
                        true, true, false, true, true, true, false);

        assertThat(BackupConsistencyPolicy.validateForBackup(filesOnly).valid()).isFalse();
    }

    @Test
    void derivedOnlyBackupIsNeverComplete() {
        BackupConsistencyPolicy.BackupManifest derivedOnly =
                new BackupConsistencyPolicy.BackupManifest(
                        false, false, false, false, true, true, true);

        assertThat(BackupConsistencyPolicy.validateForBackup(derivedOnly).valid())
                .isFalse();
    }

    @Test
    void secretsInBackupFailClosed() {
        BackupConsistencyPolicy.BackupManifest withSecrets =
                new BackupConsistencyPolicy.BackupManifest(
                        true, true, true, true, false, true, false);

        assertThat(BackupConsistencyPolicy.validateForBackup(withSecrets).valid()).isFalse();
    }

    @Test
    void databaseCopyWithoutWalCheckpointFailsClosed() {
        BackupConsistencyPolicy.BackupManifest uncheckpointed =
                new BackupConsistencyPolicy.BackupManifest(
                        true, true, true, true, true, false, false);

        assertThat(BackupConsistencyPolicy.validateForBackup(uncheckpointed).valid()).isFalse();
    }

    @Test
    void completeRestoreSetValidates() {
        BackupConsistencyPolicy.BackupValidation validation =
                BackupConsistencyPolicy.validateForRestore(complete(), completeSizes());

        assertThat(validation.valid()).isTrue();
    }

    @Test
    void partialRestoreFailsClosed() {
        Map<String, Long> partial = Map.of(
                PersistentStateClassifier.VAULT, 128L,
                PersistentStateClassifier.KNOWLEDGE_DB, 4096L);

        assertThat(BackupConsistencyPolicy.validateForRestore(complete(), partial).valid())
                .isFalse();
    }

    @Test
    void corruptEmptyRestoreFailsClosed() {
        Map<String, Long> corrupt = Map.of(
                PersistentStateClassifier.VAULT, 128L,
                PersistentStateClassifier.ARCHIVE, 128L,
                PersistentStateClassifier.KNOWLEDGE_DB, 0L,
                PersistentStateClassifier.DEPLOYMENT_CONFIG, 64L);

        assertThat(BackupConsistencyPolicy.validateForRestore(complete(), corrupt).valid())
                .isFalse();
    }

    @Test
    void restoreReasonsStayOperatorSafe() {
        BackupConsistencyPolicy.BackupValidation validation =
                BackupConsistencyPolicy.validateForRestore(complete(), Map.of());

        assertThat(validation.valid()).isFalse();
        assertThat(validation.reason())
                .doesNotContain("/", "sk-", "Bearer", "RID", "jdbc:", "SELECT");
    }
}
