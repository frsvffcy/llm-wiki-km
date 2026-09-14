package org.km.llmwiki.wiki;

/** Lost the repair dedup race: the authoritative existing proposal wins. */
public class DuplicateRepairProposalException extends RuntimeException {

    public DuplicateRepairProposalException(String message) {
        super(message);
    }
}
