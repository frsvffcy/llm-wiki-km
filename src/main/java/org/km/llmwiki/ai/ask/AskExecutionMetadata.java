package org.km.llmwiki.ai.ask;

/** Bounded, non-content execution metadata retained for observability and later API mapping. */
public record AskExecutionMetadata(
        int retrievedEvidenceItems,
        int contextEvidenceItems,
        int contextCodePoints,
        boolean contextTruncated
) {
    public AskExecutionMetadata {
        if (retrievedEvidenceItems < 0 || contextEvidenceItems < 0 || contextCodePoints < 0) {
            throw new IllegalArgumentException("Ask execution counts must not be negative");
        }
    }
}
