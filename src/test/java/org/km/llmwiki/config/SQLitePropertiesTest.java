package org.km.llmwiki.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class SQLitePropertiesTest {

    @Test
    void usesTheDocumentedDefaultBusyTimeout() {
        assertThat(new SQLiteProperties().getBusyTimeout()).isEqualTo(5000);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 5000, Integer.MAX_VALUE})
    void acceptsPositiveBusyTimeout(int busyTimeout) {
        SQLiteProperties properties = new SQLiteProperties();

        properties.setBusyTimeout(busyTimeout);

        assertThat(properties.getBusyTimeout()).isEqualTo(busyTimeout);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0})
    void rejectsNonPositiveBusyTimeout(int busyTimeout) {
        SQLiteProperties properties = new SQLiteProperties();

        assertThatThrownBy(() -> properties.setBusyTimeout(busyTimeout))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SQLite busy timeout must be greater than zero");
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0})
    void rejectsNonPositiveBusyTimeoutDuringPropertyBinding(int busyTimeout) {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(PropertyBindingConfiguration.class)
                .withPropertyValues("app.persistence.sqlite.busy-timeout=" + busyTimeout);

        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasRootCauseMessage("SQLite busy timeout must be greater than zero");
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SQLiteProperties.class)
    static class PropertyBindingConfiguration {
    }
}
