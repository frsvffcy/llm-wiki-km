package org.km.llmwiki.search;

public record SearchCorpusHealth(SearchCorpus corpus, SearchHealthStatus status, long indexed,
                                 long missing, long stale, long orphan, long failed,
                                 FtsRebuildState rebuildState) {
}
