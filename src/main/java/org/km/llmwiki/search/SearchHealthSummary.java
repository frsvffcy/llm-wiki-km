package org.km.llmwiki.search;

public record SearchHealthSummary(long indexedWiki, long indexedSourceChunks, long missing,
                                  long stale, long orphan, long failed) {
}
