package org.km.llmwiki.wiki;

public record StoredWikiPublishAttempt(long id, long workspaceId, long draftId, long proposalId,
                                       String idempotencyKey, String startedAt) {
}
