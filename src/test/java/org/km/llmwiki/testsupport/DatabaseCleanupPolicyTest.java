package org.km.llmwiki.testsupport;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class DatabaseCleanupPolicyTest {

    @Test
    void reportsANewApplicationTableThatIsMissingFromCleanup() {
        Set<String> schemaTables = Set.of("workspace", "search_index_contract", "future_application_table");

        assertThat(IsolatedIntegrationTest.DatabaseCleanupPolicy.uncoveredApplicationTables(schemaTables))
                .containsExactly("future_application_table");
    }

    @Test
    void doesNotTreatFlywayOrFtsShadowTablesAsApplicationRows() {
        Set<String> schemaTables = Set.of("flyway_schema_history", "knowledge_fts_data", "source_fts_idx");

        assertThat(IsolatedIntegrationTest.DatabaseCleanupPolicy.uncoveredApplicationTables(schemaTables))
                .isEmpty();
    }
}
