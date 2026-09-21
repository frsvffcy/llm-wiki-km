package org.km.llmwiki.processing;

/** Bounded internal projection used to determine whether one document has active background work. */
public record ProcessingJobItemState(
        ProcessingJobItemStatus status,
        String currentStep,
        String errorCode,
        String errorMessage) {
}
