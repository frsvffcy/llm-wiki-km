package org.km.llmwiki.wiki;

import java.util.EnumSet;
import java.util.Set;

/** Review lifecycle for one persisted Wiki Draft. Publish transitions are reserved for later stories. */
public enum WikiDraftStatus {
    DRAFT,
    READY,
    PUBLISHED,
    INVALIDATED;

    private static final Set<WikiDraftStatus> FROM_DRAFT = EnumSet.of(READY, INVALIDATED);
    private static final Set<WikiDraftStatus> FROM_READY = EnumSet.of(PUBLISHED, INVALIDATED);

    public boolean canTransitionTo(WikiDraftStatus next) {
        if (next == null) {
            return false;
        }
        return switch (this) {
            case DRAFT -> FROM_DRAFT.contains(next);
            case READY -> FROM_READY.contains(next);
            case PUBLISHED, INVALIDATED -> false;
        };
    }

    public void requireTransitionTo(WikiDraftStatus next) {
        if (!canTransitionTo(next)) {
            throw new WikiDraftLifecycleException("Illegal Wiki Draft status transition: " + this + " -> " + next);
        }
    }

    public boolean publishReady() {
        return this == READY;
    }
}
