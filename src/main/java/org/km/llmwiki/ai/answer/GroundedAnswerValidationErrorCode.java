package org.km.llmwiki.ai.answer;

/** Stable, bounded diagnostics for grounded response contract failures. */
public enum GroundedAnswerValidationErrorCode {
    MALFORMED_JSON,
    RESPONSE_NOT_OBJECT,
    REQUIRED_FIELD_MISSING,
    FIELD_TYPE_INVALID,
    ANSWER_TEXT_INVALID,
    CITATION_INVALID,
    UNKNOWN_CITATION_ID,
    INSUFFICIENT_EVIDENCE_CONFLICT,
    METADATA_INVALID,
    USAGE_INVALID,
    RESPONSE_TOO_LARGE
}
