package org.km.llmwiki.search.vector;

import org.km.llmwiki.search.SearchCorpus;

/** Provider- and extension-neutral semantic candidate request. */
public record VectorCandidateSearchQuery(String query, SearchCorpus corpus, int limit,
                                         Long documentId) {

    public static final int MAX_LIMIT = 200;

    public VectorCandidateSearchQuery(String query, SearchCorpus corpus, int limit) {
        this(query, corpus, limit, null);
    }

    public VectorCandidateSearchQuery {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Vector candidate query must not be blank");
        }
        if (corpus == null) {
            throw new IllegalArgumentException("Vector candidate corpus is required");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("Vector candidate limit must be between 1 and "
                    + MAX_LIMIT);
        }
        if (documentId != null && documentId <= 0) {
            throw new IllegalArgumentException("Vector candidate documentId must be positive");
        }
    }
}
