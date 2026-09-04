package org.km.llmwiki.search.embedding;

/** Safe projection result for orchestration and repair reporting. */
public record EmbeddingProjectionResult(EmbeddingProjectionOperationStatus status, long workspaceId,
                                        EmbeddingEvidenceKind evidenceKind, String stableId,
                                        String canonicalContentHash, String failureType,
                                        String failureDetail) {
}
