package org.km.llmwiki.rag;

import org.km.llmwiki.search.SearchCandidate;

/** A provider-neutral candidate with its rank in one signal list. */
public record RankingInput(CandidateSignal signal, SearchCandidate candidate, int rank) {
    public RankingInput {
        if (signal == null || candidate == null || rank < 1) {
            throw new IllegalArgumentException("signal, candidate, and positive rank are required");
        }
    }

    public String stableIdentity() {
        return candidate.kind().name() + ":" + candidate.stableId();
    }
}
