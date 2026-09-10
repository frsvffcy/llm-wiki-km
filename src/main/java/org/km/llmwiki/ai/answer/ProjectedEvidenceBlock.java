package org.km.llmwiki.ai.answer;

/**
 * Safe, typed projection metadata for one evidence block. It carries no content, no
 * filesystem paths, no raw backend identifiers, and no provider details: it only records how
 * the canonical block (by citation id and authority identity) was represented in the
 * ephemeral provider context.
 */
public record ProjectedEvidenceBlock(
        String citationId,
        String authorityIdentity,
        ProjectionKind projectionKind,
        int originalCodePoints,
        int projectedCodePoints,
        boolean contentCompacted
) {
    public ProjectedEvidenceBlock {
        if (citationId == null || citationId.isBlank()
                || authorityIdentity == null || authorityIdentity.isBlank()
                || projectionKind == null) {
            throw new IllegalArgumentException(
                    "projected block citation id, identity, and projection kind are required");
        }
        if (originalCodePoints < 0 || projectedCodePoints < 0) {
            throw new IllegalArgumentException("projection code-point counts must not be negative");
        }
    }
}
