package org.km.llmwiki.search.embedding;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.ai.embedding.EmbeddingInput;
import org.km.llmwiki.ai.embedding.EmbeddingVector;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class EmbeddingProjectionFreshnessTest {

    private static final long WORKSPACE_ID = 11L;
    private static final String HASH = "a".repeat(64);
    private static final String STABLE_ID = "same-stable-id";
    private static final String PROVIDER = "test-provider";
    private static final String MODEL = "test-model";
    private static final int DIMENSION = 2;

    @Test
    void acceptsOnlyACompleteMatchingFiniteProjection() {
        EmbeddingProjectionIdentity expected = identity();

        assertThat(EmbeddingProjectionFreshness.isFresh(projection(expected,
                EmbeddingProjectionStatus.FRESH, vector(1d, 2d)), expected)).isTrue();
        assertThat(EmbeddingProjectionFreshness.isFresh(projection(expected,
                EmbeddingProjectionStatus.FAILED, null), expected)).isFalse();
    }

    @Test
    void anyFreshnessKeyChangeMakesProjectionStale() {
        EmbeddingProjectionIdentity expected = identity();
        StoredEmbeddingProjection stored = projection(expected,
                EmbeddingProjectionStatus.FRESH, vector(1d, 2d));

        assertThat(EmbeddingProjectionFreshness.isFresh(stored,
                new EmbeddingProjectionIdentity(12L, expected.evidenceKind(), STABLE_ID, HASH,
                        PROVIDER, MODEL, DIMENSION, expected.projectionVersion()))).isFalse();
        assertThat(EmbeddingProjectionFreshness.isFresh(stored,
                new EmbeddingProjectionIdentity(WORKSPACE_ID, EmbeddingEvidenceKind.SOURCE_CHUNK,
                        STABLE_ID, HASH, PROVIDER, MODEL, DIMENSION, expected.projectionVersion()))).isFalse();
        assertThat(EmbeddingProjectionFreshness.isFresh(stored,
                new EmbeddingProjectionIdentity(WORKSPACE_ID, expected.evidenceKind(), "other-id", HASH,
                        PROVIDER, MODEL, DIMENSION, expected.projectionVersion()))).isFalse();
        assertThat(EmbeddingProjectionFreshness.isFresh(stored,
                new EmbeddingProjectionIdentity(WORKSPACE_ID, expected.evidenceKind(), STABLE_ID,
                        "b".repeat(64), PROVIDER, MODEL, DIMENSION, expected.projectionVersion()))).isFalse();
        assertThat(EmbeddingProjectionFreshness.isFresh(stored,
                new EmbeddingProjectionIdentity(WORKSPACE_ID, expected.evidenceKind(), STABLE_ID, HASH,
                        "other-provider", MODEL, DIMENSION, expected.projectionVersion()))).isFalse();
        assertThat(EmbeddingProjectionFreshness.isFresh(stored,
                new EmbeddingProjectionIdentity(WORKSPACE_ID, expected.evidenceKind(), STABLE_ID, HASH,
                        PROVIDER, "other-model", DIMENSION, expected.projectionVersion()))).isFalse();
        assertThat(EmbeddingProjectionFreshness.isFresh(stored,
                new EmbeddingProjectionIdentity(WORKSPACE_ID, expected.evidenceKind(), STABLE_ID, HASH,
                        PROVIDER, MODEL, 3, expected.projectionVersion()))).isFalse();
        assertThat(EmbeddingProjectionFreshness.isFresh(stored,
                new EmbeddingProjectionIdentity(WORKSPACE_ID, expected.evidenceKind(), STABLE_ID, HASH,
                        PROVIDER, MODEL, DIMENSION, "embedding-projection-v2"))).isFalse();
    }

    @Test
    void rejectsCorruptAndNonFiniteStoredVectorsAsStale() {
        EmbeddingProjectionIdentity expected = identity();

        assertThat(EmbeddingProjectionFreshness.isFresh(projection(expected,
                EmbeddingProjectionStatus.FRESH, new byte[]{1, 2, 3}), expected)).isFalse();
        assertThat(EmbeddingProjectionFreshness.isFresh(projection(expected,
                EmbeddingProjectionStatus.FRESH, encoded(Double.NaN, 2d)), expected)).isFalse();
        assertThat(EmbeddingProjectionFreshness.isFresh(projection(expected,
                EmbeddingProjectionStatus.FRESH, encoded(Double.POSITIVE_INFINITY, 2d)), expected)).isFalse();
    }

    @Test
    void doesNotConfuseMissingProjectionWithMissingCanonicalContent() {
        assertThat(EmbeddingProjectionFreshness.isFresh(null, identity())).isFalse();
    }

    private static EmbeddingProjectionIdentity identity() {
        return new EmbeddingProjectionIdentity(WORKSPACE_ID, EmbeddingEvidenceKind.WIKI,
                STABLE_ID, HASH, PROVIDER, MODEL, DIMENSION, EmbeddingProjectionContract.VERSION);
    }

    private static StoredEmbeddingProjection projection(EmbeddingProjectionIdentity identity,
                                                         EmbeddingProjectionStatus status,
                                                         byte[] vectorBlob) {
        return new StoredEmbeddingProjection(1L, identity.workspaceId(), identity.evidenceKind(),
                identity.stableId(), identity.canonicalContentHash(), identity.embeddingProvider(),
                identity.embeddingModel(), identity.dimension(), identity.projectionVersion(), status,
                EmbeddingProjectionContract.VECTOR_ENCODING, vectorBlob, 1,
                "2026-09-02T00:00:00Z", "2026-09-02T00:00:00Z", null, null,
                "2026-09-02T00:00:00Z", "2026-09-02T00:00:00Z");
    }

    private static byte[] vector(double... values) {
        return EmbeddingVectorCodec.encode(new EmbeddingVector(
                new EmbeddingInput("projection input").identity(), List.of(values[0], values[1])));
    }

    private static byte[] encoded(double... values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * Double.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (double value : values) {
            buffer.putDouble(value);
        }
        return buffer.array();
    }
}
