package org.km.llmwiki.ai.query;

import org.km.llmwiki.rag.EvidenceBundle;
import org.km.llmwiki.rag.ModalityOutcome;
import org.km.llmwiki.search.CjkBigramProjector;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/** #390-supported shape: a multi-term lexical miss/crowd-out containing question/filler language. */
@Component
public final class SingleRewritePolicyV1 implements QueryTransformationPolicy {
    public static final String VERSION = "query-transform-single-rewrite-v1";
    private static final List<String> FILLERS = List.of(
            "怎麼", "如何", "請問", "預設值", "設定", "要怎樣", "what is", "how do", "please");

    @Override public String version() { return VERSION; }
    @Override public boolean enabled() { return true; }

    @Override
    public QueryTransformationApplicability applicability(String originalQuery,
                                                           EvidenceBundle originalEvidence) {
        if (originalQuery == null || originalEvidence == null
                || (originalEvidence.diagnostics().strategy()
                    != org.km.llmwiki.rag.RetrievalStrategy.HYBRID
                && originalEvidence.diagnostics().strategy()
                    != org.km.llmwiki.rag.RetrievalStrategy.FUSED)) {
            return QueryTransformationApplicability.RETRIEVAL_SHAPE_UNSUPPORTED;
        }
        String normalized = originalQuery.toLowerCase(Locale.ROOT);
        if (CjkBigramProjector.tokens(originalQuery).size() < 3
                || FILLERS.stream().noneMatch(normalized::contains)) {
            return QueryTransformationApplicability.QUERY_SHAPE_UNSUPPORTED;
        }
        if (originalEvidence.diagnostics().lexicalOutcome() != ModalityOutcome.EMPTY
                || originalEvidence.items().isEmpty()) {
            return QueryTransformationApplicability.RETRIEVAL_SHAPE_UNSUPPORTED;
        }
        return QueryTransformationApplicability.LEXICAL_MISS_CROWD_OUT;
    }
}
