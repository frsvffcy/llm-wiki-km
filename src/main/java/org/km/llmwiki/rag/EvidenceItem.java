package org.km.llmwiki.rag;

/**
 * Traceable, provider-neutral evidence assembled from an FTS candidate and revalidated authority.
 *
 * <p>Corpus-specific fields are nullable only for the other evidence kind.
 */
public record EvidenceItem(
        EvidenceKind kind,
        String stableId,
        EvidenceWorkspace workspace,
        double score,
        String content,
        String snippet,
        boolean contentTruncated,
        String contentHash,
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
    public EvidenceItem {
        if (kind == null || stableId == null || stableId.isBlank() || workspace == null
                || !Double.isFinite(score) || score < 0 || content == null || content.isBlank()
                || contentHash == null || contentHash.isBlank()) {
            throw new IllegalArgumentException("Evidence identity, score, content, and hash are required");
        }
    }

    public String stableIdentity() {
        return kind.name() + ":" + stableId;
    }
}
