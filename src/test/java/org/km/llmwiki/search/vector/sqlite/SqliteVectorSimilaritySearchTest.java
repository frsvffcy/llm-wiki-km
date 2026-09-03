package org.km.llmwiki.search.vector.sqlite;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.km.llmwiki.config.VectorCapabilityProperties;
import org.km.llmwiki.search.embedding.EmbeddingEvidenceKind;
import org.km.llmwiki.search.embedding.EmbeddingProjectionContract;
import org.km.llmwiki.search.vector.VectorAvailability;
import org.km.llmwiki.search.vector.VectorCapability;
import org.km.llmwiki.search.vector.VectorCapabilityReport;
import org.km.llmwiki.search.vector.VectorCapabilityRequest;
import org.km.llmwiki.search.vector.VectorCandidateSearchUnavailableException;
import org.km.llmwiki.search.vector.VectorEncoding;
import org.km.llmwiki.search.vector.VectorExtensionLoadStatus;
import org.km.llmwiki.search.vector.VectorSimilarityQuery;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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

        assertThatThrownBy(() -> search.findNearest(query(1, 0)))
                .isSameAs(defect);
    }

    @Test
    void mapsSqliteConnectionFailureToTypedRepositoryUnavailable() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("database unavailable"));

        VectorCandidateSearchUnavailableException failure =
                org.assertj.core.api.Assertions.catchThrowableOfType(
                        () -> adapter(dataSource).findNearest(query(1, 0)),
                        VectorCandidateSearchUnavailableException.class);

        assertThat(failure).isNotNull();
        assertThat(failure.dependency())
                .isEqualTo(VectorCandidateSearchUnavailableException.Dependency.VECTOR_REPOSITORY);
        assertThat(failure.getCause()).isInstanceOf(SQLException.class);
    }

    @Test
    void buildsNativeBoundedQueryWithAllProjectionFiltersAndStableOrdering() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(any(String.class))).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(false);

        SqliteVectorSimilaritySearch search = adapter(dataSource);
        assertThat(search.findNearest(query(3, 6))).isEmpty();

        var sqlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sqlCaptor.capture());
        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("vec_distance_cosine(vector_search_blob, ?)")
                .contains("FROM embedding_projection")
                .contains("workspace_id = ?")
                .contains("evidence_kind IN (?, ?)")
                .contains("embedding_provider = ?")
                .contains("embedding_model = ?")
                .contains("dimension = ?")
                .contains("projection_version = ?")
                .contains("generation_status = 'FRESH'")
                .contains("vector_search_blob IS NOT NULL")
                .contains("ORDER BY distance ASC, evidence_kind ASC, stable_id ASC")
                .contains("LIMIT ? OFFSET ?")
                .doesNotContain("CREATE VIRTUAL TABLE")
                .doesNotContain("TEMP");
        verify(statement).setInt(9, 3);
        verify(statement).setInt(10, 6);
    }

    @Test
    void normalizesCosineDistanceToHigherIsBetterSimilarity() {
        assertThat(SqliteVectorSimilaritySearch.normalizeCosineDistance(0.0d)).isEqualTo(1.0d);
        assertThat(SqliteVectorSimilaritySearch.normalizeCosineDistance(1.0d)).isEqualTo(0.5d);
        assertThat(SqliteVectorSimilaritySearch.normalizeCosineDistance(2.0d)).isEqualTo(0.0d);
    }

    private VectorSimilarityQuery query(int limit, int offset) {
        return new VectorSimilarityQuery(7L,
                List.of(EmbeddingEvidenceKind.WIKI, EmbeddingEvidenceKind.SOURCE_CHUNK),
                "test-provider", "test-model", VECTOR.size(), EmbeddingProjectionContract.VERSION,
                VECTOR, limit, offset, true);
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
        VectorExtensionLoader loader = mock(VectorExtensionLoader.class);
        when(loader.load(any(Connection.class), any(Path.class)))
                .thenReturn(new VectorExtensionLoader.LoadResult(VectorExtensionLoadStatus.LOADED,
                        "loaded"));
        return new SqliteVectorSimilaritySearch(dataSource, properties, capability, loader);
    }
}
