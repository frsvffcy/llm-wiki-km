package org.km.llmwiki.graph;

import java.util.Locale;

/**
 * Revalidation material for canonical authority.
 *
 * <p>Freshness is deliberately separate from stable identity: a revision or content hash may
 * change while the same semantic entity remains the same entity.
 */
public record GraphFreshness(Integer revision, String contentHash) {

    public GraphFreshness {
        if (revision == null && (contentHash == null || contentHash.isBlank())) {
            throw new IllegalArgumentException("Graph freshness requires a revision or content hash");
        }
        if (revision != null && revision < 1) {
            throw new IllegalArgumentException("Graph freshness revision must be positive");
        }
        if (contentHash != null) {
            contentHash = contentHash.trim().toLowerCase(Locale.ROOT);
            if (!contentHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Graph content hash must be SHA-256 hex");
            }
        }
    }

    public static GraphFreshness revision(int revision) {
        return new GraphFreshness(revision, null);
    }

    public static GraphFreshness contentHash(String contentHash) {
        return new GraphFreshness(null, contentHash);
    }
}
