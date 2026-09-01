package org.km.llmwiki.ai.ask;

/** Stable top-level outcome of one Ask orchestration. */
public enum AskStatus {
    ANSWERED,
    INSUFFICIENT_EVIDENCE,
    FAILED
}
