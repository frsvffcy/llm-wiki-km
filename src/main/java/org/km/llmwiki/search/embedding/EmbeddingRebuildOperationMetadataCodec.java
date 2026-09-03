package org.km.llmwiki.search.embedding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.km.llmwiki.search.SearchCorpus;

import java.util.Iterator;
import java.util.Optional;

/** Encodes the small, allow-listed immutable metadata owned by embedding rebuild jobs. */
public final class EmbeddingRebuildOperationMetadataCodec {
    private static final String SCHEMA = "embedding-rebuild-operation-v1";
    private static final int MAX_METADATA_LENGTH = 256;
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);

    private EmbeddingRebuildOperationMetadataCodec() {
    }

    /** Returns the canonical representation captured in a new EMBEDDING_REBUILD job. */
    public static String encode(SearchCorpus corpus) {
        if (corpus == null) {
            throw new IllegalArgumentException("Embedding rebuild corpus is required");
        }
        return "{\"schema\":\"" + SCHEMA + "\",\"corpus\":\"" + corpus.name() + "\"}";
    }

    /**
     * Decodes only this feature's exact schema and fields. Missing or invalid legacy data is
     * intentionally represented as empty rather than inferred from mutable readiness state.
     */
    public static Optional<SearchCorpus> decode(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()
                || metadataJson.length() > MAX_METADATA_LENGTH) {
            return Optional.empty();
        }
        try {
            JsonNode root = MAPPER.readTree(metadataJson);
            if (root == null || !root.isObject() || root.size() != 2
                    || !hasOnlyAllowedFields(root)) {
                return Optional.empty();
            }
            JsonNode schema = root.get("schema");
            JsonNode corpus = root.get("corpus");
            if (schema == null || !schema.isTextual() || !SCHEMA.equals(schema.textValue())
                    || corpus == null || !corpus.isTextual()) {
                return Optional.empty();
            }
            SearchCorpus decoded = switch (corpus.textValue()) {
                case "WIKI" -> SearchCorpus.WIKI;
                case "SOURCE" -> SearchCorpus.SOURCE;
                case "ALL" -> SearchCorpus.ALL;
                default -> null;
            };
            if (decoded == null || !metadataJson.equals(encode(decoded))) {
                return Optional.empty();
            }
            return Optional.of(decoded);
        } catch (JsonProcessingException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static boolean hasOnlyAllowedFields(JsonNode root) {
        Iterator<String> fields = root.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!"schema".equals(field) && !"corpus".equals(field)) {
                return false;
            }
        }
        return true;
    }
}
