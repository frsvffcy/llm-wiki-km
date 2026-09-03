package org.km.llmwiki.search.vector.sqlite;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.km.llmwiki.config.VectorCapabilityProperties;
import org.km.llmwiki.search.vector.VectorAvailability;
import org.km.llmwiki.search.vector.VectorCapability;
import org.km.llmwiki.search.vector.VectorCapabilityFailure;
import org.km.llmwiki.search.vector.VectorCapabilityReport;
import org.km.llmwiki.search.vector.VectorCapabilityRequest;
import org.km.llmwiki.search.vector.VectorCandidateSearchUnavailableException;
import org.km.llmwiki.search.vector.VectorEncoding;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("unit")
class SqliteVectorSimilaritySearchTest {

    private static final List<Double> VECTOR = List.of(0.25d, 0.75d);

    @TempDir
    Path temporaryDirectory;

    @Test
    void propagatesUnexpectedRuntimeExceptionFromJdbcBoundary() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        IllegalStateException defect = new IllegalStateException("programming defect");
        when(dataSource.getConnection()).thenThrow(defect);

        SqliteVectorSimilaritySearch search = adapter(dataSource);

        assertThatThrownBy(() -> search.findNearest(VECTOR, List.of(), 1))
                .isSameAs(defect);
    }

    @Test
    void mapsSqliteConnectionFailureToTypedRepositoryUnavailable() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("database unavailable"));

        VectorCandidateSearchUnavailableException failure =
                org.assertj.core.api.Assertions.catchThrowableOfType(
                        () -> adapter(dataSource).findNearest(VECTOR, List.of(), 1),
                        VectorCandidateSearchUnavailableException.class);

        assertThat(failure).isNotNull();
        assertThat(failure.dependency())
                .isEqualTo(VectorCandidateSearchUnavailableException.Dependency.VECTOR_REPOSITORY);
        assertThat(failure.getCause()).isInstanceOf(SQLException.class);
    }

    private SqliteVectorSimilaritySearch adapter(DataSource dataSource) throws Exception {
        Path extension = Files.createFile(temporaryDirectory.resolve("vec0.dylib"));
        VectorCapabilityProperties properties = new VectorCapabilityProperties();
        properties.setEnabled(true);
        properties.setExtensionPath(extension);
        VectorCapability capability = mock(VectorCapability.class);
        VectorCapabilityRequest request = new VectorCapabilityRequest(VECTOR.size(),
                VectorEncoding.FLOAT32);
        when(capability.inspect(request)).thenReturn(VectorCapabilityReport.available(request,
                Set.of(VectorEncoding.FLOAT32), "v0.1.9"));
        return new SqliteVectorSimilaritySearch(dataSource, properties, capability,
                mock(VectorExtensionLoader.class));
    }
}
