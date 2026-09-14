package org.km.llmwiki.ai.query;

/** Deterministic application-owned reason for whether a rewrite policy may execute. */
public enum QueryTransformationApplicability {
    POLICY_DISABLED(false),
    QUERY_SHAPE_UNSUPPORTED(false),
    RETRIEVAL_SHAPE_UNSUPPORTED(false),
    LEXICAL_MISS_CROWD_OUT(true);

    private final boolean applicable;

    QueryTransformationApplicability(boolean applicable) {
        this.applicable = applicable;
    }

    public boolean applicable() {
        return applicable;
    }
}
