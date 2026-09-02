package org.km.llmwiki.rag;

import org.km.llmwiki.search.SearchCorpus;

/**
 * Backward-compatible retrieval presets.  The corpus and strategy are exposed separately by
 * {@link RetrievalRequest}; these values remain the public compatibility surface used by Ask.
 */
public enum RetrievalMode {
    WIKI_ONLY(SearchCorpus.WIKI, RetrievalStrategy.LEXICAL),
    SOURCE_ONLY(SearchCorpus.SOURCE, RetrievalStrategy.LEXICAL),
    HYBRID_FTS(SearchCorpus.ALL, RetrievalStrategy.LEXICAL),
    SEMANTIC_WIKI(SearchCorpus.WIKI, RetrievalStrategy.SEMANTIC),
    SEMANTIC_SOURCE(SearchCorpus.SOURCE, RetrievalStrategy.SEMANTIC),
    HYBRID_VECTOR(SearchCorpus.ALL, RetrievalStrategy.HYBRID);

    private final SearchCorpus searchCorpus;
    private final RetrievalStrategy strategy;

    RetrievalMode(SearchCorpus searchCorpus, RetrievalStrategy strategy) {
        this.searchCorpus = searchCorpus;
        this.strategy = strategy;
    }

    public SearchCorpus searchCorpus() {
        return searchCorpus;
    }

    public RetrievalStrategy strategy() {
        return strategy;
    }
}
