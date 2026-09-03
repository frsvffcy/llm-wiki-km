package org.km.llmwiki.search.vector.sqlite;

import org.jooq.exception.DataAccessException;
import org.km.llmwiki.config.VectorCapabilityProperties;
import org.km.llmwiki.search.embedding.EmbeddingEvidenceKind;
import org.km.llmwiki.search.vector.VectorAvailability;
import org.km.llmwiki.search.vector.VectorCapability;
import org.km.llmwiki.search.vector.VectorCapabilityFailure;
import org.km.llmwiki.search.vector.VectorCapabilityReport;
import org.km.llmwiki.search.vector.VectorCapabilityRequest;
import org.km.llmwiki.search.vector.VectorCandidateSearchUnavailableException;
import org.km.llmwiki.search.vector.VectorEncoding;
import org.km.llmwiki.search.vector.VectorExtensionLoadStatus;
import org.km.llmwiki.search.vector.VectorSimilarityMatch;
import org.km.llmwiki.search.vector.VectorSimilarityQuery;
import org.km.llmwiki.search.vector.VectorSimilaritySearch;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** SQLite adapter for the provider-neutral, storage-level bounded KNN contract. */
@Service
public final class SqliteVectorSimilaritySearch implements VectorSimilaritySearch {

    private final DataSource dataSource;
    private final VectorCapabilityProperties properties;
    private final VectorCapability capability;
    private final VectorExtensionLoader extensionLoader;

    @Autowired
    public SqliteVectorSimilaritySearch(DataSource dataSource,
                                        VectorCapabilityProperties properties,
                                        VectorCapability capability) {
        this(dataSource, properties, capability, new SqliteNativeExtensionLoader());
    }

    SqliteVectorSimilaritySearch(DataSource dataSource,
                                 VectorCapabilityProperties properties,
                                 VectorCapability capability,
                                 VectorExtensionLoader extensionLoader) {
        this.dataSource = dataSource;
        this.properties = properties;
        this.capability = capability;
        this.extensionLoader = extensionLoader;
    }

    @Override
    public List<VectorSimilarityMatch> findNearest(VectorSimilarityQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("Vector similarity query is required");
        }
        VectorCapabilityReport report = capability.inspect(
                new VectorCapabilityRequest(query.dimension(), VectorEncoding.FLOAT32));
        if (report == null || report.availability() != VectorAvailability.AVAILABLE) {
            VectorCapabilityFailure failure = report == null
                    ? VectorCapabilityFailure.CAPABILITY_CHECK_FAILED : report.failure();
            throw unavailable(failure, new IllegalStateException(
                    report == null ? "Vector capability probe returned no report" : report.detail()));
        }

        Path extensionPath = properties.getExtensionPath();
        if (extensionPath == null) {
            throw unavailable(VectorCapabilityFailure.EXTENSION_PATH_MISSING,
                    new IllegalStateException("Vector extension path is not configured"));
        }
        try (Connection connection = dataSource.getConnection()) {
            VectorExtensionLoader.LoadResult load = extensionLoader.load(connection,
                    extensionPath.toAbsolutePath().normalize());
            if (load == null || load.status() != VectorExtensionLoadStatus.LOADED) {
                throw unavailable(VectorCapabilityFailure.EXTENSION_LOAD_FAILED,
                        new IllegalStateException(load == null ? "Vector extension loader returned no result"
                                : load.detail()));
            }
            return query(connection, query);
        } catch (VectorCandidateSearchUnavailableException failure) {
            throw failure;
        } catch (SQLException | DataAccessException failure) {
            throw new VectorCandidateSearchUnavailableException(
                    VectorCandidateSearchUnavailableException.Dependency.VECTOR_REPOSITORY,
                    failure);
        }
    }

    private static List<VectorSimilarityMatch> query(Connection connection,
                                                     VectorSimilarityQuery query) throws SQLException {
        String placeholders = "?, ".repeat(query.evidenceKinds().size() - 1) + "?";
        String freshnessPredicate = query.freshOnly()
                ? "AND generation_status = 'FRESH' AND vector_encoding = 'FLOAT64_LE' "
                : "";
        String sql = "SELECT evidence_kind, stable_id, canonical_content_hash, "
                + "embedding_provider, embedding_model, dimension, projection_version, "
                + "vec_distance_cosine(vector_search_blob, ?) AS distance "
                + "FROM embedding_projection "
                + "WHERE workspace_id = ? "
                + "AND evidence_kind IN (" + placeholders + ") "
                + "AND embedding_provider = ? AND embedding_model = ? AND dimension = ? "
                + "AND projection_version = ? " + freshnessPredicate
                + "AND vector_search_blob IS NOT NULL "
                + "ORDER BY distance ASC, evidence_kind ASC, stable_id ASC LIMIT ? OFFSET ?";

        List<VectorSimilarityMatch> matches = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameter = 1;
            statement.setBytes(parameter++, encodeFloat32(query.queryVector()));
            statement.setLong(parameter++, query.workspaceId());
            for (EmbeddingEvidenceKind kind : query.evidenceKinds()) {
                statement.setString(parameter++, kind.name());
            }
            statement.setString(parameter++, query.embeddingProvider());
            statement.setString(parameter++, query.embeddingModel());
            statement.setInt(parameter++, query.dimension());
            statement.setString(parameter++, query.projectionVersion());
            statement.setInt(parameter++, query.limit());
            statement.setInt(parameter, query.offset());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    double distance = result.getDouble("distance");
                    if (result.wasNull() || !Double.isFinite(distance)) {
                        throw new SQLException("Vector distance result is invalid");
                    }
                    matches.add(new VectorSimilarityMatch(
                            EmbeddingEvidenceKind.valueOf(result.getString("evidence_kind")),
                            result.getString("stable_id"),
                            result.getString("canonical_content_hash"),
                            result.getString("embedding_provider"),
                            result.getString("embedding_model"),
                            result.getInt("dimension"),
                            result.getString("projection_version"),
                            normalizeCosineDistance(distance)));
                }
            }
        }
        return List.copyOf(matches);
    }

    /** sqlite-vec cosine distance is in [0, 2]; the application contract is [0, 1], larger wins. */
    static double normalizeCosineDistance(double distance) {
        if (!Double.isFinite(distance)) {
            throw new IllegalArgumentException("Vector distance must be finite");
        }
        return Math.max(0.0d, Math.min(1.0d, 1.0d - distance / 2.0d));
    }

    private static byte[] encodeFloat32(List<Double> values) {
        ByteBuffer buffer = ByteBuffer.allocate(Math.multiplyExact(values.size(), Float.BYTES))
                .order(ByteOrder.LITTLE_ENDIAN);
        for (double value : values) {
            float converted = (float) value;
            if (!Float.isFinite(converted)) {
                throw new IllegalArgumentException("Vector value cannot be represented as FLOAT32");
            }
            buffer.putFloat(converted);
        }
        return buffer.array();
    }

    private static VectorCandidateSearchUnavailableException unavailable(
            VectorCapabilityFailure failure, Throwable cause) {
        return new VectorCandidateSearchUnavailableException(
                VectorCandidateSearchUnavailableException.Dependency.VECTOR_CAPABILITY,
                failure, cause);
    }
}
