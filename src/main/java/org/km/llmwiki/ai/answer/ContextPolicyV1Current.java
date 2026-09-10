package org.km.llmwiki.ai.answer;

import org.km.llmwiki.rag.EvidenceBundle;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Current production compaction policy: {@code context-policy-v1-current} is the identity
 * projection of the bounded baseline assembled by {@link AnswerContextAssembler}. It never
 * compacts content, never emits {@link ProjectionKind#EXTRACTIVE} or
 * {@link ProjectionKind#NO_OP}, and is byte-equivalent to the pre-#309 Ask context packing.
 * Switching the active policy version requires recorded evaluation evidence plus the
 * regression gate; rolling back is a policy-version change, not a rebuild.
 */
@Component
public final class ContextPolicyV1Current implements AnswerContextCompactionPolicy {

    public static final String VERSION = "context-policy-v1-current";

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public PolicyProjection project(EvidenceBundle evidence, AnswerContext baseline,
                                     AnswerContextBudget budget) {
        List<AnswerContextBlock> blocks = new ArrayList<>(baseline.blocks().size());
        List<ProjectionKind> kinds = new ArrayList<>(baseline.blocks().size());
        for (AnswerContextBlock block : baseline.blocks()) {
            blocks.add(block);
            kinds.add(block.contentTruncated() ? ProjectionKind.TRUNCATED : ProjectionKind.VERBATIM);
        }
        return new PolicyProjection(blocks, kinds);
    }
}
