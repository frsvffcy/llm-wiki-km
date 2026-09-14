package org.km.llmwiki.system;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class PersistentStateClassifierTest {

    @ParameterizedTest
    @ValueSource(strings = {
            PersistentStateClassifier.VAULT,
            PersistentStateClassifier.ARCHIVE,
            PersistentStateClassifier.KNOWLEDGE_DB,
            PersistentStateClassifier.WORKSPACE_STATE,
            PersistentStateClassifier.DEPLOYMENT_CONFIG})
    void authoritativeStateRequiresBackup(String logicalName) {
        PersistentStateClassifier.Classification classification =
                PersistentStateClassifier.classify(logicalName);

        assertThat(classification.stateClass())
                .isEqualTo(PersistentStateClassifier.StateClass.AUTHORITATIVE);
        assertThat(classification.backupRequired()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            PersistentStateClassifier.FTS5_PROJECTION,
            PersistentStateClassifier.VECTOR_PROJECTION,
            PersistentStateClassifier.GRAPH_PROJECTION,
            PersistentStateClassifier.DERIVED_CACHE,
            PersistentStateClassifier.LOGS,
            PersistentStateClassifier.TEMP})
    void derivedStateIsNeverBackupAuthority(String logicalName) {
        PersistentStateClassifier.Classification classification =
                PersistentStateClassifier.classify(logicalName);

        assertThat(classification.stateClass())
                .isEqualTo(PersistentStateClassifier.StateClass.REBUILDABLE);
        assertThat(classification.backupRequired()).isFalse();
    }

    @Test
    void unknownStateFailsClosed() {
        assertThatThrownBy(() -> PersistentStateClassifier.classify("postgres-cluster"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullStateFailsClosed() {
        assertThatThrownBy(() -> PersistentStateClassifier.classify(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
