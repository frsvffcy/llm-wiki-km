package org.km.llmwiki.search.embedding;

import org.km.llmwiki.ai.embedding.EmbeddingVector;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Provider- and extension-neutral binary representation used until a future vector store is chosen. */
public final class EmbeddingVectorCodec {

    private EmbeddingVectorCodec() {
    }

    public static byte[] encode(EmbeddingVector vector) {
        ByteBuffer buffer = ByteBuffer.allocate(Math.multiplyExact(vector.values().size(), Double.BYTES))
                .order(ByteOrder.LITTLE_ENDIAN);
        vector.values().forEach(buffer::putDouble);
        return buffer.array();
    }

    public static EmbeddingVector decode(String inputIdentity, byte[] encoded, int dimension) {
        if (encoded == null || dimension <= 0 || encoded.length != Math.multiplyExact(dimension, Double.BYTES)) {
            throw new IllegalArgumentException("Encoded embedding vector has an invalid dimension");
        }
        ByteBuffer buffer = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        List<Double> values = new ArrayList<>(dimension);
        for (int index = 0; index < dimension; index++) {
            values.add(buffer.getDouble());
        }
        return new EmbeddingVector(inputIdentity, values);
    }
}
