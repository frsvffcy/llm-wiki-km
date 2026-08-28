package org.km.llmwiki.wiki;

import org.junit.jupiter.api.Test;
import org.km.llmwiki.ai.LlmProposalAction;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WikiActionPlannerTest {

    private final WikiActionPlanner planner = new WikiActionPlanner(
            new WikiTargetResolver(new WikiPathContract()::resolveLogicalPath, new EmptyCatalog()));

    @Test
    void linkOnlyIgnoreAndReviewNeverPlanMainWikiWrites() {
        for (LlmProposalAction action : List.of(
                LlmProposalAction.LINK_ONLY, LlmProposalAction.IGNORE, LlmProposalAction.REVIEW)) {
            WikiActionPlan plan = planner.planNonWrite(12L, action, List.of(4L, 8L));

            assertThat(plan.action()).isEqualTo(action);
            assertThat(plan.outcome()).isEqualTo(WikiActionPlanOutcome.NO_MAIN_WIKI_WRITE);
            assertThat(plan.target()).isNull();
            assertThat(plan.plansMainWikiWrite()).isFalse();
        }
    }

    @Test
    void createAndMergeCannotBypassValidatedWritePlanning() {
        assertThatThrownBy(() -> planner.planNonWrite(12L, LlmProposalAction.CREATE, List.of(4L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> planner.planNonWrite(12L, LlmProposalAction.MERGE, List.of(4L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static final class EmptyCatalog implements WikiTargetCatalog {
        @Override
        public boolean existsAtCanonicalPath(long workspaceId, String logicalRelativePath) {
            return false;
        }

        @Override
        public List<WikiTargetRecord> findExact(WikiTargetReference reference) {
            return List.of();
        }
    }
}
