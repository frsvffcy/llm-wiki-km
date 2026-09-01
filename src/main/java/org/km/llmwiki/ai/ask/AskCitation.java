package org.km.llmwiki.ai.ask;

import org.km.llmwiki.ai.answer.AnswerContextBlock;
import org.km.llmwiki.ai.answer.AnswerContextProvenance;
import org.km.llmwiki.rag.EvidenceKind;

import java.util.Objects;

/** Citation projected from an application-owned context block to authoritative provenance. */
public record AskCitation(
        String citationId,
        EvidenceKind evidenceKind,
        String authorityIdentity,
        String contentHash,
        AnswerContextProvenance provenance
) {

    public AskCitation {
        if (citationId == null || citationId.isBlank() || evidenceKind == null
                || authorityIdentity == null || authorityIdentity.isBlank()
                || contentHash == null || contentHash.isBlank()
                || provenance == null) {
            throw new IllegalArgumentException("citation identity and provenance are required");
        }
    }

    public static AskCitation from(AnswerContextBlock block) {
        Objects.requireNonNull(block, "context block must not be null");
        return new AskCitation(block.citationId(), block.evidenceKind(),
                block.authorityIdentity(), block.contentHash(), block.provenance());
    }
}
