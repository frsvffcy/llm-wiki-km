package org.km.llmwiki.ai.query;

import org.km.llmwiki.rag.EvidenceBundle;

/** Qualified evidence plus the bounded transformation execution outcome. */
public record QueryTransformationResult(
        EvidenceBundle evidence,
        QueryTransformationExecution execution
) { }
