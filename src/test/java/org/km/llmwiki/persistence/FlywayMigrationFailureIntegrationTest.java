package org.km.llmwiki.persistence;

import org.km.llmwiki.LlmWikiKmApplication;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@Tag("integration")
class FlywayMigrationFailureIntegrationTest {

    @Test
    void applicationStartupAbortsWhenMigrationFails() {
        String databasePath = "target/test-data/" + UUID.randomUUID() + "/knowledge.db";

        Throwable failure = catchThrowable(() -> new SpringApplicationBuilder(LlmWikiKmApplication.class)
                .run(
                        "--spring.main.web-application-type=none",
                        "--app.persistence.sqlite.path=" + databasePath,
                        "--spring.flyway.locations=classpath:db/migration-failure-test"));

        assertThat(failure).isNotNull();
        assertThat(containsCause(failure, FlywayException.class))
                .as("startup failure should be caused by Flyway migration error")
                .isTrue();
    }

    private static boolean containsCause(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
