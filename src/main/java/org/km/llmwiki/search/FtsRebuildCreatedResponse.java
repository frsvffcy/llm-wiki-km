package org.km.llmwiki.search;

public record FtsRebuildCreatedResponse(String jobId, String status, SearchCorpus corpus,
                                        long workspaceId) {
}
