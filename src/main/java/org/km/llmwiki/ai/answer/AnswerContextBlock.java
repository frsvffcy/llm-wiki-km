package org.km.llmwiki.ai.answer;

import org.km.llmwiki.rag.EvidenceKind;

/** One bounded evidence block with an application-owned citation identity. */
public record AnswerContextBlock(
        String citationId,
        EvidenceKind evidenceKind,
        String authorityIdentity,
        String content,
        boolean contentTruncated,
        String contentHash,
        AnswerContextProvenance provenance
) {
    public AnswerContextBlock {
        if (citationId == null || citationId.isBlank()
                || evidenceKind == null
                || authorityIdentity == null || authorityIdentity.isBlank()
                || content == null || content.isBlank()
                || contentHash == null || contentHash.isBlank()
                || provenance == null) {
            throw new IllegalArgumentException("context block identity, content, hash, and provenance are required");
        }
    }
}
