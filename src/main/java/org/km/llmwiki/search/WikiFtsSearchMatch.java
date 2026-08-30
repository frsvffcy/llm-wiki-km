package org.km.llmwiki.search;

record WikiFtsSearchMatch(long workspaceId, String knowledgeId, String title,
                          String pageType, String path, int revision,
                          String snippet, double rawRank) {
}
