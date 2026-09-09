package org.km.llmwiki.processing;

/** Generic not-found result for workspace- and job-type-scoped operation queries. */
public class ProcessingOperationNotFoundException extends RuntimeException {
    public ProcessingOperationNotFoundException() {
        super("Processing job not found");
    }
}
