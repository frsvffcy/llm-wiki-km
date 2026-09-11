package org.km.llmwiki.ai.provider;

/**
 * Application-owned data-category disclosure for provider egress transparency. Categories
 * describe which types of content may leave the provider boundary; they never expose payloads,
 * full prompts, or evidence content.
 */
public enum ProviderEgressCategory {
    /** The user's question text is sent to the answer provider. */
    QUESTION_TEXT,
    /** Authority-admitted evidence-derived provider-bound context representations are sent. */
    EVIDENCE_CONTEXT_REPRESENTATION,
    /** Application-owned grounding/instruction context is sent (category level, not verbatim). */
    INSTRUCTION_CONTEXT,
    /** Non-secret generation settings (model parameters) are sent with the request. */
    GENERATION_SETTINGS,
    /** The provider may return answer text and usage metadata. */
    PROVIDER_RESPONSE_METADATA,
    /** Application-selected text representations are sent to the embedding provider. */
    EMBEDDING_INPUT_REPRESENTATION
}
