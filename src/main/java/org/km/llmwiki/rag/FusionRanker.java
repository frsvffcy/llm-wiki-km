package org.km.llmwiki.rag;

import org.km.llmwiki.search.SearchCandidate;

import java.util.List;

/** Provider-neutral boundary for deterministic lexical/vector candidate fusion. */
public interface FusionRanker {

    List<SearchCandidate> fuse(List<SearchCandidate> lexical,
                               List<SearchCandidate> vector,
                               int limit);
}
