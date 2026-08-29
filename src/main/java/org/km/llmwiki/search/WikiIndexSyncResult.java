package org.km.llmwiki.search;

public record WikiIndexSyncResult(WikiIndexSyncStatus status, long workspaceId, long knowledgePageId,
                                  String detail) {
}
