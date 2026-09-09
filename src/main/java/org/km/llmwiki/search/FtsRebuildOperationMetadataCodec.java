package org.km.llmwiki.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Iterator;
import java.util.Optional;

/** Encodes the bounded immutable corpus metadata owned by FTS rebuild jobs. */
public final class FtsRebuildOperationMetadataCodec {
    private static final String SCHEMA = "fts-rebuild-operation-v1";
    private static final int MAX_METADATA_LENGTH = 256;
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);

    private FtsRebuildOperationMetadataCodec() {
    }

    public static String encode(SearchCorpus corpus) {
        if (corpus == null) {
            throw new IllegalArgumentException("FTS rebuild corpus is required");
        }
        return "{\"schema\":\"" + SCHEMA + "\",\"corpus\":\"" + corpus.name() + "\"}";
    }

    /** Invalid or legacy metadata is unknown; mutable rebuild state is never consulted. */
    public static Optional<SearchCorpus> decode(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()
                || metadataJson.length() > MAX_METADATA_LENGTH) {
            return Optional.empty();
        }
        try {
            JsonNode root = MAPPER.readTree(metadataJson);
            if (root == null || !root.isObject() || root.size() != 2 || !hasOnlyAllowedFields(root)) {
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
