package org.km.llmwiki.source;

/**
 * Versioned, deterministic, provider-free normalization step for selected Unicode
 * format characters (#412, decision authority #380).
 *
 * <p>A policy only transforms the artifact subset approved by #380; it never performs
 * retrieval, persistence, provider calls, or locale-dependent casing. The same input
 * and policy version must always produce the same output. Behaviour changes require
 * a new version — never a silent mutation of an existing version's semantics.
 */
public interface NormalizationPolicy {

    /** Stable policy version; changing behaviour requires a new version. */
    String version();

    /**
     * Applies the policy's selected-character transform to already NFC-normalized
     * content. Implementations must be deterministic, provider-free and
     * locale-independent.
     */
    String apply(String content);
}
