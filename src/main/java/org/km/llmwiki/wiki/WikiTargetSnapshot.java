package org.km.llmwiki.wiki;

import java.util.Locale;

/** Immutable target captured by an action plan for later optimistic-lock verification. */
public record WikiTargetSnapshot(Kind kind, String stableIdentifier, String title,
                                 WikiPageType pageType, String logicalRelativePath,
                                 String currentContentHash) {

    public enum Kind {
        CREATE_NEW,
        EXISTING
    }

    public WikiTargetSnapshot {
        if (kind == null || title == null || title.isBlank() || pageType == null
                || logicalRelativePath == null || logicalRelativePath.isBlank()) {
            throw new IllegalArgumentException("Wiki target snapshot requires canonical title, type, and path");
        }
        WikiPage canonical = WikiPage.create(title, pageType, null, null, null, null);
        if (!canonical.logicalRelativePath().equals(logicalRelativePath)) {
            throw new IllegalArgumentException("Wiki target snapshot violates canonical path invariants");
        }
        if (kind == Kind.CREATE_NEW) {
            if (stableIdentifier != null || currentContentHash != null) {
                throw new IllegalArgumentException("CREATE target must not claim an existing identity or hash");
            }
        } else if (stableIdentifier == null || stableIdentifier.isBlank()
                || currentContentHash == null || !currentContentHash.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Existing target requires stable identity and SHA-256 hash");
        } else {
            currentContentHash = currentContentHash.toLowerCase(Locale.ROOT);
        }
    }

    public static WikiTargetSnapshot createNew(String title, WikiPageType pageType, String logicalRelativePath) {
        return new WikiTargetSnapshot(Kind.CREATE_NEW, null, title, pageType, logicalRelativePath, null);
    }

    public static WikiTargetSnapshot existing(WikiTargetRecord target) {
        return new WikiTargetSnapshot(Kind.EXISTING, target.stableIdentifier(), target.title(), target.pageType(),
                target.logicalRelativePath(), target.currentContentHash());
    }
}
