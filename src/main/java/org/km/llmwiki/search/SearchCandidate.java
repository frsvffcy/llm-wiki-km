package org.km.llmwiki.search;

/**
 * Provider-neutral application candidate shared by Search API mapping and retrieval.
 *
 * <p>This is deliberately separate from the REST-facing {@link SearchResult}.
 */
public record SearchCandidate(
        SearchResultKind kind,
        String stableId,
        double score,
        String snippet,
        SearchWorkspaceProvenance workspace,
        String knowledgeId,
        String title,
        String pageType,
        String path,
        Integer revision,
        Long sourceChunkId,
        Long documentId,
        String documentName,
        Integer chunkNo,
        Integer pageNo,
        String section,
        String headingPath
) {
}
