package org.km.llmwiki.source;

import java.util.Map;
import java.util.Objects;

/**
 * Library-neutral extraction result shared by all document parsers.
 */
public record ParsedDocument(String content, Map<String, String> metadata) {

    public ParsedDocument {
        content = Objects.requireNonNull(content, "content must not be null");
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata must not be null"));
    }
}
