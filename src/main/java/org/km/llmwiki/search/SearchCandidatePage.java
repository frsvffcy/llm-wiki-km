package org.km.llmwiki.search;

import java.util.List;

/** Deterministic application-level page of FTS candidates. */
public record SearchCandidatePage(List<SearchCandidate> items, int page, int size, long totalElements) {

    public SearchCandidatePage {
        items = List.copyOf(items);
    }
}
