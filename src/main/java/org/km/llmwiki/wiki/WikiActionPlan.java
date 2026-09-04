package org.km.llmwiki.wiki;

import org.km.llmwiki.ai.LlmProposalAction;

import java.util.HashSet;
import java.util.List;

/** Deterministic, replayable plan produced before preview or publish. */
public record WikiActionPlan(long proposalId, LlmProposalAction action, WikiActionPlanOutcome outcome,
                             WikiTargetSnapshot target, List<Long> sourceChunkIds) {

    public WikiActionPlan {
        if (proposalId <= 0 || action == null || outcome == null) {
            throw new IllegalArgumentException("Wiki action plan requires proposal, action, and outcome");
        }
        sourceChunkIds = List.copyOf(sourceChunkIds);
        if (sourceChunkIds.isEmpty() || new HashSet<>(sourceChunkIds).size() != sourceChunkIds.size()
                || sourceChunkIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("Wiki action plan requires unique positive evidence ids");
        }
        switch (action) {
            case CREATE -> {
                if (outcome != WikiActionPlanOutcome.CREATE_MAIN_WIKI || target == null
                        || target.kind() != WikiTargetSnapshot.Kind.CREATE_NEW) {
                    throw new IllegalArgumentException("CREATE plan requires one canonical new target");
                }
            }
            case MERGE -> {
                if (outcome != WikiActionPlanOutcome.MERGE_MAIN_WIKI || target == null
                        || target.kind() != WikiTargetSnapshot.Kind.EXISTING) {
                    throw new IllegalArgumentException("MERGE plan requires one existing target snapshot");
                }
            }
            case LINK_ONLY, IGNORE, REVIEW -> {
                if (outcome != WikiActionPlanOutcome.NO_MAIN_WIKI_WRITE || target != null) {
                    throw new IllegalArgumentException(action + " must not plan a main Wiki write");
                }
            }
        }
    }

    public boolean plansMainWikiWrite() {
        return outcome != WikiActionPlanOutcome.NO_MAIN_WIKI_WRITE;
    }
}
