package org.km.llmwiki.wiki;

import org.km.llmwiki.ai.LlmProposalAction;
import org.springframework.stereotype.Component;

import java.util.List;

/** Separates deterministic action planning from later preview and write execution. */
@Component
public class WikiActionPlanner {

    private final WikiTargetResolver targetResolver;

    public WikiActionPlanner(WikiTargetResolver targetResolver) {
        this.targetResolver = targetResolver;
    }

    public WikiActionPlan planWrite(long activeWorkspaceId, WikiDraft draft) {
        return switch (draft.action()) {
            case CREATE -> new WikiActionPlan(draft.proposalId(), draft.action(),
                    WikiActionPlanOutcome.CREATE_MAIN_WIKI,
                    targetResolver.resolveCreate(activeWorkspaceId, draft), draft.sourceChunkIds());
            case MERGE -> new WikiActionPlan(draft.proposalId(), draft.action(),
                    WikiActionPlanOutcome.MERGE_MAIN_WIKI,
                    targetResolver.resolveMerge(activeWorkspaceId, draft), draft.sourceChunkIds());
            case LINK_ONLY, IGNORE, REVIEW -> throw new IllegalArgumentException(
                    "Renderable WikiDraft cannot carry a non-write action");
        };
    }

    public WikiActionPlan planNonWrite(long proposalId, LlmProposalAction action, List<Long> sourceChunkIds) {
        if (action != LlmProposalAction.LINK_ONLY && action != LlmProposalAction.IGNORE
                && action != LlmProposalAction.REVIEW) {
            throw new IllegalArgumentException("CREATE and MERGE require a validated WikiDraft");
        }
        return new WikiActionPlan(proposalId, action, WikiActionPlanOutcome.NO_MAIN_WIKI_WRITE,
                null, sourceChunkIds);
    }
}
