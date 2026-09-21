package org.km.llmwiki.source;

/** Application-owned projection of whether one Inbox document is usable for lexical search. */
public record DocumentUsabilityReadiness(
        Status status,
        boolean searchReady,
        NextAction nextAction) {

    public enum Status {
        PROCESSING,
        READY_TO_USE,
        NOT_PROCESSED,
        INDEX_PENDING,
        NOT_SEARCHABLE,
        NEED_OCR,
        UNSUPPORTED,
        FAILED,
        DUPLICATE
    }

    public enum NextAction {
        WAIT,
        START_USING,
        RETRY_PROCESSING,
        PROVIDE_OCR,
        USE_EXISTING_DOCUMENT,
        NONE
    }
}
