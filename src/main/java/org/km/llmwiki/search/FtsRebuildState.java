package org.km.llmwiki.search;

public record FtsRebuildState(long workspaceId, SearchCorpus corpus, FtsRebuildStatus status,
                              long processingJobId, int indexedCount, int failedCount,
                              String failureDetail, String startedAt, String completedAt,
                              String updatedAt) {
}
