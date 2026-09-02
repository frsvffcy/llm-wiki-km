package org.km.llmwiki.search.vector.sqlite;

import org.km.llmwiki.config.VectorCapabilityProperties;
import org.km.llmwiki.search.vector.VectorAvailability;
import org.km.llmwiki.search.vector.VectorCapability;
import org.km.llmwiki.search.vector.VectorCapabilityFailure;
import org.km.llmwiki.search.vector.VectorCapabilityReport;
import org.km.llmwiki.search.vector.VectorCapabilityRequest;
import org.km.llmwiki.search.vector.VectorCandidateSearchUnavailableException;
import org.km.llmwiki.search.vector.VectorEncoding;
import org.km.llmwiki.search.vector.VectorSimilarityEntry;
import org.km.llmwiki.search.vector.VectorSimilarityMatch;
import org.km.llmwiki.search.vector.VectorSimilaritySearch;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** SQLite adapter for the provider-neutral similarity contract. */
@Service
public final class SqliteVectorSimilaritySearch implements VectorSimilaritySearch {

    private static final int MAX_LIMIT = 200;
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
    public List<VectorSimilarityMatch> findNearest(List<Double> queryVector,
                                                   List<VectorSimilarityEntry> candidates,
                                                   int limit) {
        validateInput(queryVector, candidates, limit);
        VectorCapabilityReport report = capability.inspect(
                new VectorCapabilityRequest(queryVector.size(), VectorEncoding.FLOAT32));
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
            if (load.status() != org.km.llmwiki.search.vector.VectorExtensionLoadStatus.LOADED) {
                throw unavailable(VectorCapabilityFailure.EXTENSION_LOAD_FAILED,
                        new IllegalStateException(load.detail()));
            }
            return query(connection, queryVector, candidates, limit);
        } catch (VectorCandidateSearchUnavailableException failure) {
            throw failure;
        } catch (SQLException | RuntimeException failure) {
            throw new VectorCandidateSearchUnavailableException(
                    VectorCandidateSearchUnavailableException.Dependency.VECTOR_REPOSITORY,
                    failure);
        }
    }

    private static void validateInput(List<Double> queryVector,
                                      List<VectorSimilarityEntry> candidates,
                                      int limit) {
        if (queryVector == null || queryVector.isEmpty()
                || queryVector.stream().anyMatch(value -> value == null || !Double.isFinite(value))) {
            throw new IllegalArgumentException("Query vector is invalid");
        }
        if (candidates == null || limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("Candidate list or limit is invalid");
        }
        int dimension = queryVector.size();
        if (candidates.stream().anyMatch(entry -> entry.values().size() != dimension)) {
            throw new IllegalArgumentException("Vector dimensions do not match");
        }
    }

    private static List<VectorSimilarityMatch> query(Connection connection,
                                                     List<Double> queryVector,
                                                     List<VectorSimilarityEntry> candidates,
                                                     int limit) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TEMP TABLE vector_candidate_search ("
                    + "identity TEXT PRIMARY KEY, embedding BLOB NOT NULL)");
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO vector_candidate_search(identity, embedding) VALUES (?, ?)")) {
            for (VectorSimilarityEntry candidate : candidates) {
                insert.setString(1, candidate.identity());
                insert.setBytes(2, encodeFloat32(candidate.values()));
                insert.addBatch();
            }
            insert.executeBatch();
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        String sql = "SELECT identity, vec_distance_cosine(embedding, ?) AS distance "
                + "FROM vector_candidate_search ORDER BY distance ASC, identity ASC LIMIT ?";
        List<VectorSimilarityMatch> matches = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, encodeFloat32(queryVector));
            statement.setInt(2, limit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String identity = result.getString("identity");
                    double distance = result.getDouble("distance");
                    if (result.wasNull() || !Double.isFinite(distance)) {
                        throw new SQLException("Vector distance result is invalid");
                    }
                    double similarity = Math.max(0.0d, Math.min(1.0d, 1.0d - distance / 2.0d));
                    matches.add(new VectorSimilarityMatch(identity, similarity));
                }
            }
        }
        matches.sort(Comparator.comparingDouble(VectorSimilarityMatch::similarity).reversed()
                .thenComparing(VectorSimilarityMatch::identity));
        return List.copyOf(matches);
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
