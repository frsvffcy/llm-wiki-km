package org.km.llmwiki.rag;

/**
 * Explicit per-modality outcome for one fused retrieval. A degraded or unavailable modality is a
 * diagnostic, never a silent zero result: an infrastructure failure of one modality must not be
 * reported as {@code INSUFFICIENT_EVIDENCE} of the whole fusion.
 */
public enum ModalityOutcome {
    /** Modality completed and contributed at least one selected evidence item. */
    CONTRIBUTED,
    /** Modality completed normally with no selected evidence. */
    EMPTY,
    /** Modality infrastructure/readiness failure; lexical baseline is unaffected. */
    UNAVAILABLE,
    /** Modality completed earlier but drifted before terminal publication; evidence was dropped. */
    DEGRADED,
    /** Modality is disabled by configuration or request. */
    DISABLED,
    /** Graph projection exists but is not in a serving-ready state. */
    NOT_READY
}
