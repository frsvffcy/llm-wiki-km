package org.km.llmwiki.wiki;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Review lifecycle for a persisted knowledge proposal. */
public enum KnowledgeProposalStatus {
    DRAFT,
    REVIEW,
    APPROVED,
    REJECTED;

    private static final Set<KnowledgeProposalStatus> FROM_DRAFT = EnumSet.of(REVIEW, REJECTED);
    private static final Set<KnowledgeProposalStatus> FROM_REVIEW = EnumSet.of(APPROVED, REJECTED);

    /**
     * The single transition authority (#370): every legal target is derived here and
     * nowhere else — validation ({@link #canTransitionTo}) and the REST capability
     * projection ({@code allowedTransitions} on the review response) both read this
     * derivation, so the domain machine and the Browser render contract cannot drift.
     */
    public List<KnowledgeProposalStatus> allowedTransitions() {
        return switch (this) {
            case DRAFT -> List.copyOf(FROM_DRAFT);
            case REVIEW -> List.copyOf(FROM_REVIEW);
            case APPROVED, REJECTED -> List.of();
        };
    }

    public boolean canTransitionTo(KnowledgeProposalStatus next) {
        return next != null && allowedTransitions().contains(next);
    }

    public void requireTransitionTo(KnowledgeProposalStatus next) {
        if (!canTransitionTo(next)) {
            throw new IllegalArgumentException("Illegal knowledge proposal status transition: " + this + " -> " + next);
        }
    }
}
