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

    /**
     * Repair-baseline write plan (#384): same outcome and lifecycle as
     * {@link #planWrite} for MERGE, except the target snapshot pins the actual drifted
     * file bytes (see {@link WikiTargetResolver#resolveMergeForRepair}) so a repair
     * draft can be created against drift it is about to revert. Selected only for
     * repair-kind proposals by the planning service.
     */
    public WikiActionPlan planWriteRepair(long activeWorkspaceId, WikiDraft draft) {
        if (draft.action() != LlmProposalAction.MERGE) {
            throw new IllegalArgumentException("Repair baseline plans require a MERGE WikiDraft");
        }
        return new WikiActionPlan(draft.proposalId(), draft.action(),
                WikiActionPlanOutcome.MERGE_MAIN_WIKI,
                targetResolver.resolveMergeForRepair(activeWorkspaceId, draft), draft.sourceChunkIds());
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
