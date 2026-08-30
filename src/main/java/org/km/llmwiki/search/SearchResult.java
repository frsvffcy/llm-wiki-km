package org.km.llmwiki.search;

/** Unified API/application result with corpus-specific provenance in nullable fields. */
public record SearchResult(
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
