package org.km.llmwiki.wiki;

/** Proposal 的 tags 不在人類可編輯範圍（例如 REPAIR lineage 的 deterministic plan）。 */
public class ProposalTagsNotEditableException extends RuntimeException {

    public ProposalTagsNotEditableException(String message) {
        super(message);
    }
}
