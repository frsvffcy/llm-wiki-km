package org.km.llmwiki.search;

import java.util.List;

public record SearchHealthResult(long workspaceId, String workspaceName, SearchCorpus corpus,
                                 SearchHealthStatus status, SearchHealthSummary summary,
                                 List<SearchCorpusHealth> corpora, String checkedAt) {
    public SearchHealthResult {
        corpora = List.copyOf(corpora);
    }
}
