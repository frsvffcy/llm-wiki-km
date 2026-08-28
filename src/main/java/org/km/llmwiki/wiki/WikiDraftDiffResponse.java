package org.km.llmwiki.wiki;

/** Auditable diff between the captured #90 target baseline and deterministic rendered Draft. */
public record WikiDraftDiffResponse(long id, WikiDraftStatus status, boolean publishReady, String targetPath,
                                    String baseContentHash, String renderedContentHash,
                                    String currentContent, String renderedContent, String unifiedDiff) {
}
