package org.km.llmwiki.ai;

/** Actions allowed for later human-reviewed knowledge proposal processing. */
public enum LlmProposalAction {
    CREATE,
    MERGE,
    LINK_ONLY,
    IGNORE,
    REVIEW
}
