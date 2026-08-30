package org.km.llmwiki.rag;

import org.km.llmwiki.search.SearchCorpus;

/** FTS-only retrieval modes available before vector or graph phases. */
public enum RetrievalMode {
    WIKI_ONLY(SearchCorpus.WIKI),
    SOURCE_ONLY(SearchCorpus.SOURCE),
    HYBRID_FTS(SearchCorpus.ALL);

    private final SearchCorpus searchCorpus;

    RetrievalMode(SearchCorpus searchCorpus) {
        this.searchCorpus = searchCorpus;
    }

    SearchCorpus searchCorpus() {
        return searchCorpus;
    }
}
