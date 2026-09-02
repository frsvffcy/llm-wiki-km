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
        String indexedContentHash,
        String sourceDocumentFingerprint,
        Integer sourceEligibleChunkCount,
        Long sourceChunkId,
        Long documentId,
        String documentName,
        Integer chunkNo,
        Integer pageNo,
        String section,
        String headingPath,
        String embeddingProvider,
        String embeddingModel,
        Integer embeddingDimension,
        String embeddingProjectionVersion
) {

    /** Keeps the established FTS candidate construction surface unchanged. */
    public SearchCandidate(SearchResultKind kind, String stableId, double score, String snippet,
                           SearchWorkspaceProvenance workspace, String knowledgeId, String title,
                           String pageType, String path, Integer revision,
                           String indexedContentHash, String sourceDocumentFingerprint,
                           Integer sourceEligibleChunkCount, Long sourceChunkId, Long documentId,
                           String documentName, Integer chunkNo, Integer pageNo, String section,
                           String headingPath) {
        this(kind, stableId, score, snippet, workspace, knowledgeId, title, pageType, path,
                revision, indexedContentHash, sourceDocumentFingerprint,
                sourceEligibleChunkCount, sourceChunkId, documentId, documentName, chunkNo,
                pageNo, section, headingPath, null, null, null, null);
    }
}
