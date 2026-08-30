package org.km.llmwiki.search;

/** Typed application query that does not depend on HTTP request or response DTOs. */
public record SearchQuery(String query, SearchCorpus corpus, String pageType,
                          Long documentId, int page, int size) {
}
