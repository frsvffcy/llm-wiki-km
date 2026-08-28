package org.km.llmwiki.wiki;

/** Controlled reasons that make a persisted draft ineligible for a later publish operation. */
public enum WikiDraftInvalidationReason {
    MANUAL,
    SUPERSEDED_BY_REGENERATION,
    SOURCE_PROPOSAL_INVALID,
    TARGET_CHANGED
}
