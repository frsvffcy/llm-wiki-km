package org.km.llmwiki.rag;

/**
 * Stable, application-owned reason codes for lexical/vector authority revalidation and the
 * terminal publication guard. Reasons are produced by explicit canonical comparison points —
 * never inferred from exception messages. Graph-channel rejections reuse the existing
 * {@code GraphEvidenceRejectionReason} taxonomy and graph projection drift reuses the graph
 * projection failure codes.
 */
public enum AuthorityRejectionReason {
    WORKSPACE_MISMATCH,
    IDENTITY_MISMATCH,
    AUTHORITY_MISSING,
    STALE_REVISION,
    INELIGIBLE
}
