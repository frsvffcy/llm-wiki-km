package org.km.llmwiki.search.vector.sqlite;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.km.llmwiki.config.VectorCapabilityProperties;
import org.km.llmwiki.search.vector.VectorAvailability;
import org.km.llmwiki.search.vector.VectorCapabilityFailure;
import org.km.llmwiki.search.vector.VectorCapabilityRequest;
import org.km.llmwiki.search.vector.VectorEncoding;
import org.km.llmwiki.search.vector.VectorExtensionLoadStatus;
import org.km.llmwiki.search.vector.VectorHealth;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
class SqliteVectorCapabilityAdapterTest {

    private static final VectorCapabilityRequest REQUEST =
            new VectorCapabilityRequest(3, VectorEncoding.FLOAT32);

    @TempDir
    Path temporaryDirectory;

    @Test
    void disabledCapabilityIsTypedAndDoesNotOpenAConnection() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        VectorCapabilityProperties properties = properties(false, null);

        var report = adapter(dataSource, properties, mock(VectorExtensionLoader.class)).inspect(REQUEST);

        assertThat(report.availability()).isEqualTo(VectorAvailability.UNAVAILABLE);
        assertThat(report.health()).isEqualTo(VectorHealth.UNHEALTHY);
        assertThat(report.extensionLoadStatus()).isEqualTo(VectorExtensionLoadStatus.NOT_ATTEMPTED);
        assertThat(report.failure()).isEqualTo(VectorCapabilityFailure.DISABLED);
        verify(dataSource, never()).getConnection();
    }

    @Test
    void invalidDimensionAndUnsupportedEncodingAreNotNoResultSignals() {
        Path extension = temporaryExtension();
        var adapter = adapter(mock(DataSource.class), properties(true, extension),
                mock(VectorExtensionLoader.class));

        var invalidDimension = adapter.inspect(new VectorCapabilityRequest(0, VectorEncoding.FLOAT32));
        var unsupportedEncoding = adapter.inspect(new VectorCapabilityRequest(3, VectorEncoding.FLOAT64));

        assertThat(invalidDimension.failure()).isEqualTo(VectorCapabilityFailure.INVALID_DIMENSION);
        assertThat(unsupportedEncoding.failure()).isEqualTo(VectorCapabilityFailure.UNSUPPORTED_ENCODING);
        assertThat(invalidDimension.availability()).isNotEqualTo(VectorAvailability.AVAILABLE);
        assertThat(unsupportedEncoding.availability()).isNotEqualTo(VectorAvailability.AVAILABLE);
    }

    @Test
    void missingOrInvalidExtensionPathIsDeterministicallyUnavailable() {
        DataSource dataSource = mock(DataSource.class);
        var loader = mock(VectorExtensionLoader.class);

        var missing = adapter(dataSource, properties(true, null), loader).inspect(REQUEST);
        var invalid = adapter(dataSource, properties(true,
                temporaryDirectory.resolve("does-not-exist.dylib")), loader).inspect(REQUEST);

        assertThat(missing.failure()).isEqualTo(VectorCapabilityFailure.EXTENSION_PATH_MISSING);
        assertThat(missing.extensionLoadStatus()).isEqualTo(VectorExtensionLoadStatus.NOT_ATTEMPTED);
        assertThat(invalid.failure()).isEqualTo(VectorCapabilityFailure.EXTENSION_PATH_INVALID);
        assertThat(invalid.extensionLoadStatus()).isEqualTo(VectorExtensionLoadStatus.NOT_ATTEMPTED);
    }

    @Test
    void extensionLoadFailureIsDifferentFromAnEmptySearchResult() {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Path extension = temporaryExtension();
        var loader = mock(VectorExtensionLoader.class);
        when(loader.load(connection, extension.toAbsolutePath().normalize()))
                .thenReturn(new VectorExtensionLoader.LoadResult(VectorExtensionLoadStatus.FAILED,
                        "test failure"));
        try {
            when(dataSource.getConnection()).thenReturn(connection);
        } catch (SQLException exception) {
            throw new AssertionError(exception);
        }

        var report = adapter(dataSource, properties(true, extension), loader).inspect(REQUEST);

        assertThat(report.availability()).isEqualTo(VectorAvailability.UNAVAILABLE);
        assertThat(report.failure()).isEqualTo(VectorCapabilityFailure.EXTENSION_LOAD_FAILED);
        assertThat(report.extensionLoadStatus()).isEqualTo(VectorExtensionLoadStatus.FAILED);
    }

    @Test
    void incompatibleExtensionVersionIsReportedBeforeCapabilityIsAdvertised() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet version = mock(ResultSet.class);
        Path extension = temporaryExtension();
        var loader = successfulLoader(connection, extension);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT vec_version()")).thenReturn(version);
        when(version.next()).thenReturn(true);
        when(version.getString(1)).thenReturn("v0.1.8");

        var report = adapter(dataSource, properties(true, extension), loader).inspect(REQUEST);

        assertThat(report.availability()).isEqualTo(VectorAvailability.UNAVAILABLE);
        assertThat(report.failure()).isEqualTo(VectorCapabilityFailure.INCOMPATIBLE_RUNTIME);
        assertThat(report.runtimeVersion()).isEqualTo("v0.1.8");
    }

    @Test
    void successfulProbeReportsOnlyProviderNeutralCapabilityFacts() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet version = mock(ResultSet.class);
        ResultSet module = mock(ResultSet.class);
        Path extension = temporaryExtension();
        var loader = successfulLoader(connection, extension);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT vec_version()")).thenReturn(version);
        when(statement.executeQuery(any())).thenReturn(version, module);
        when(version.next()).thenReturn(true);
        when(version.getString(1)).thenReturn("v0.1.9");
        when(module.next()).thenReturn(true);
        when(module.getInt(1)).thenReturn(1);

        var report = adapter(dataSource, properties(true, extension), loader).inspect(REQUEST);

        assertThat(report.availability()).isEqualTo(VectorAvailability.AVAILABLE);
        assertThat(report.health()).isEqualTo(VectorHealth.HEALTHY);
        assertThat(report.extensionLoadStatus()).isEqualTo(VectorExtensionLoadStatus.LOADED);
        assertThat(report.failure()).isEqualTo(VectorCapabilityFailure.NONE);
        assertThat(report.dimensionSupported()).isTrue();
        assertThat(report.supportedEncodings()).containsExactly(VectorEncoding.FLOAT32);
        assertThat(report.detail()).doesNotContain("vec0", "load_extension");
    }

    private Path temporaryExtension() {
        try {
            return Files.createFile(temporaryDirectory.resolve("vec0.dylib"));
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static VectorCapabilityProperties properties(boolean enabled, Path extension) {
        VectorCapabilityProperties properties = new VectorCapabilityProperties();
        properties.setEnabled(enabled);
        properties.setExtensionPath(extension);
        return properties;
    }

    private static SqliteVectorCapabilityAdapter adapter(DataSource dataSource,
                                                          VectorCapabilityProperties properties,
                                                          VectorExtensionLoader loader) {
        return new SqliteVectorCapabilityAdapter(dataSource, properties, loader);
    }

    private static VectorExtensionLoader successfulLoader(Connection connection, Path extension) {
        var loader = mock(VectorExtensionLoader.class);
        try {
            when(loader.load(connection, extension.toAbsolutePath().normalize()))
                    .thenReturn(new VectorExtensionLoader.LoadResult(VectorExtensionLoadStatus.LOADED,
                            "test success"));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
        return loader;
    }
}
