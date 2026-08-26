package org.km.llmwiki.source;

public record RescanResponse(
        int newDocuments,
        int duplicates,
        int existing,
        int removed) {
}
