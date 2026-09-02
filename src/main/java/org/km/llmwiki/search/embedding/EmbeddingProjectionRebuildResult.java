package org.km.llmwiki.search.embedding;

/** Counts for one workspace rebuild; canonical rows are never included in these counts. */
public record EmbeddingProjectionRebuildResult(long workspaceId, int attempted, int fresh,
                                               int failed, int ineligible) {
}
