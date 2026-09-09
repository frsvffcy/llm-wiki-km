package org.km.llmwiki.rag;

import org.km.llmwiki.search.embedding.EmbeddingEvidenceKind;
import org.km.llmwiki.search.vector.VectorSimilarityMatch;
import org.km.llmwiki.search.vector.VectorSimilarityQuery;
import org.km.llmwiki.search.vector.VectorSimilaritySearch;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Deterministic in-memory KNN adapter for the offline quality benchmark. It implements the same
 * bounded KNN storage contract as the production SQLite + sqlite-vec adapter — freshness and
 * metadata filters, cosine similarity normalized into [0, 1], and the identical deterministic
 * ordering {@code distance ASC, evidence_kind ASC, stable_id ASC} — but computes distances in
 * memory over the persisted {@code embedding_projection} rows, so the benchmark needs no
 * platform-specific loadable extension. The production adapter keeps its own contract tests and
 * the CI sqlite-vec smoke evidence.
 */
final class DeterministicVectorSimilaritySearch implements VectorSimilaritySearch {

    private final JdbcClient db;

    DeterministicVectorSimilaritySearch(JdbcClient db) {
        this.db = db;
    }

    @Override
    public List<VectorSimilarityMatch> findNearest(VectorSimilarityQuery query) {
        String placeholders = "?, ".repeat(query.evidenceKinds().size() - 1) + "?";
        String freshnessPredicate = query.freshOnly()
                ? " AND generation_status = 'FRESH' AND vector_encoding = 'FLOAT64_LE' " : "";
        List<Object> parameters = new ArrayList<>();
        parameters.add(query.workspaceId());
        parameters.addAll(query.evidenceKinds());
        parameters.add(query.embeddingProvider());
        parameters.add(query.embeddingModel());
        parameters.add(query.dimension());
        parameters.add(query.projectionVersion());

        record Scored(EmbeddingEvidenceKind kind, String stableId, String hash, String provider,
                      String model, int dimension, String version, double similarity) {
        }

        List<Scored> scored;
        try {
            scored = db.sql("""
                            SELECT evidence_kind, stable_id, canonical_content_hash, embedding_provider,
                                   embedding_model, dimension, projection_version, vector_search_blob
                            FROM embedding_projection
                            WHERE workspace_id = ?
                              AND evidence_kind IN (%s) %s
                              AND embedding_provider = ? AND embedding_model = ? AND dimension = ?
                              AND projection_version = ? AND vector_search_blob IS NOT NULL
                            """.formatted(placeholders, freshnessPredicate))
                    .params(parameters.toArray())
                    .query((resultSet, rowNumber) -> new Scored(
                            EmbeddingEvidenceKind.valueOf(resultSet.getString("evidence_kind")),
                            resultSet.getString("stable_id"),
                            resultSet.getString("canonical_content_hash"),
                            resultSet.getString("embedding_provider"),
                            resultSet.getString("embedding_model"),
                            resultSet.getInt("dimension"),
                            resultSet.getString("projection_version"),
                            similarity(query.queryVector(), decode(resultSet))))
                    .list();
        } catch (org.springframework.dao.DataAccessException | IllegalStateException failure) {
            // Production parity for operational faults; a corrupt fixture blob still fails the
            // test loudly instead of being swallowed into a silent empty channel.
            throw new org.km.llmwiki.search.vector.VectorCandidateSearchUnavailableException(
                    org.km.llmwiki.search.vector.VectorCandidateSearchUnavailableException.Dependency.VECTOR_REPOSITORY,
                    failure);
        }

        scored.sort(Comparator.comparingDouble(Scored::similarity).reversed()
                .thenComparing(scoredItem -> scoredItem.kind().name())
                .thenComparing(Scored::stableId));
        List<VectorSimilarityMatch> matches = new ArrayList<>();
        for (Scored row : scored.stream().skip(query.offset()).limit(query.limit()).toList()) {
            matches.add(new VectorSimilarityMatch(row.kind(), row.stableId(), row.hash(),
                    row.provider(), row.model(), row.dimension(), row.version(), row.similarity()));
        }
        return List.copyOf(matches);
    }

    private static double[] decode(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        byte[] blob = resultSet.getBytes("vector_search_blob");
        int dimension = resultSet.getInt("dimension");
        ByteBuffer buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        double[] values = new double[dimension];
        for (int index = 0; index < dimension; index++) {
            values[index] = (double) buffer.getFloat();
        }
        return values;
    }

    private static double similarity(List<Double> queryVector, double[] stored) {
        double dot = 0.0d;
        double queryNorm = 0.0d;
        double storedNorm = 0.0d;
        for (int index = 0; index < stored.length; index++) {
            // Production compares FLOAT32-rounded values, so the fixture truncates the query
            // the same way before any ordering-relevant arithmetic.
            double queryValue = (double) (float) queryVector.get(index).doubleValue();
            stored[index] = (double) (float) stored[index];
            dot += queryValue * stored[index];
            queryNorm += queryValue * queryValue;
            storedNorm += stored[index] * stored[index];
        }
        if (queryNorm == 0.0d || storedNorm == 0.0d) {
            return 0.0d;
        }
        double cosine = dot / (Math.sqrt(queryNorm) * Math.sqrt(storedNorm));
        double distance = 1.0d - cosine;
        return Math.max(0.0d, Math.min(1.0d, 1.0d - distance / 2.0d));
    }
}
