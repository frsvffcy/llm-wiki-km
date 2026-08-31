package org.km.llmwiki.search;

/** Raw workspace-scoped Wiki FTS row used only by drift detection. */
record WikiIndexProjection(long rowId, boolean identityValid, String knowledgeId, String title,
                           String content, String normalizedTitle, String markdownPath,
                           String pageType, String pageStatus, String contentHash) {
}
