package org.km.llmwiki.wiki;

/** Explicit human repair intent for one canonical page identity (#384). */
public record CreateRepairProposalRequest(String knowledgeId) {
}
