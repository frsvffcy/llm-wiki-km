package org.km.llmwiki.wiki;

/** Lost the dedup race: an identical Ask proposal already exists (translated by the service). */
public class DuplicateAskProposalException extends RuntimeException {

    public DuplicateAskProposalException(String message) {
        super(message);
    }
}
