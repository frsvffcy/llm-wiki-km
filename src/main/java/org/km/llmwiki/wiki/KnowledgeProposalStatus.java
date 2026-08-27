package org.km.llmwiki.wiki;

import java.util.EnumSet;
import java.util.Set;

/** Review lifecycle for a persisted knowledge proposal. */
public enum KnowledgeProposalStatus {
    DRAFT,
    REVIEW,
    APPROVED,
    REJECTED;

    private static final Set<KnowledgeProposalStatus> FROM_DRAFT = EnumSet.of(REVIEW, REJECTED);
    private static final Set<KnowledgeProposalStatus> FROM_REVIEW = EnumSet.of(APPROVED, REJECTED);

    public boolean canTransitionTo(KnowledgeProposalStatus next) {
        if (next == null) {
            return false;
        }
        return switch (this) {
            case DRAFT -> FROM_DRAFT.contains(next);
            case REVIEW -> FROM_REVIEW.contains(next);
            case APPROVED, REJECTED -> false;
        };
    }

    public void requireTransitionTo(KnowledgeProposalStatus next) {
        if (!canTransitionTo(next)) {
            throw new IllegalArgumentException("Illegal knowledge proposal status transition: " + this + " -> " + next);
        }
    }
}
