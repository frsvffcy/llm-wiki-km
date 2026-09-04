package org.km.llmwiki.search.embedding;

/** Deliberately generic not-found result for unknown and cross-workspace job identifiers. */
public class ProcessingJobNotFoundException extends RuntimeException {
    public ProcessingJobNotFoundException() {
        super("Embedding rebuild job not found");
    }
}
