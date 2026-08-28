package org.km.llmwiki.wiki;

/** Read-only persistence projection for one indexed Wiki target. */
public record WikiTargetRecord(long workspaceId, String stableIdentifier, String title,
                               WikiPageType pageType, String logicalRelativePath,
                               PageStatus status, String currentContentHash) {

    public WikiTargetRecord {
        if (workspaceId <= 0 || stableIdentifier == null || stableIdentifier.isBlank()
                || title == null || title.isBlank() || pageType == null
                || logicalRelativePath == null || logicalRelativePath.isBlank()
                || status == null || currentContentHash == null
                || !currentContentHash.matches("(?i)[0-9a-f]{64}")) {
            throw invariant("Indexed Wiki target is missing required identity or hash data");
        }
        WikiPage canonical = WikiPage.create(title, pageType, null, null, null, null);
        if (!canonical.logicalRelativePath().equals(logicalRelativePath)) {
            throw invariant("Indexed Wiki target path does not match its canonical title and page type");
        }
        currentContentHash = currentContentHash.toLowerCase(java.util.Locale.ROOT);
    }

    private static WikiTargetResolutionException invariant(String message) {
        return new WikiTargetResolutionException(
                WikiTargetResolutionException.Reason.CANONICAL_INVARIANT_VIOLATION, message);
    }
}
