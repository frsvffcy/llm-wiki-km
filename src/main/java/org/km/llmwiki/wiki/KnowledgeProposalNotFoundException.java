package org.km.llmwiki.wiki;

/** Proposal 不存在於目前工作區的審核佇列時拋出。 */
public class KnowledgeProposalNotFoundException extends RuntimeException {

    public KnowledgeProposalNotFoundException(long proposalId) {
        super("Knowledge proposal not found: " + proposalId);
    }
}
