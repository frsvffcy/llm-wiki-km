package org.km.llmwiki.testsupport;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseCleanupPolicyIntegrationTest extends IsolatedIntegrationTest {

    @Test
    void retainsMigrationOwnedFtsContractAndFlywayHistoryWhileResettingApplicationState() {
        assertThat(db().sql("SELECT COUNT(*) FROM search_index_contract")
                .query(Integer.class).single()).isEqualTo(2);
        assertThat(db().sql("SELECT COUNT(*) FROM flyway_schema_history")
                .query(Integer.class).single()).isGreaterThan(0);
    }
}
