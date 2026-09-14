package org.km.llmwiki.ai.query;

import org.km.llmwiki.rag.EvidenceBundle;

/** Versioned, application-owned applicability policy. */
public interface QueryTransformationPolicy {

    String version();

    boolean enabled();

    QueryTransformationApplicability applicability(String originalQuery,
                                                    EvidenceBundle originalEvidence);
}
