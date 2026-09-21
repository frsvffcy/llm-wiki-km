package org.km.llmwiki.wiki;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #569 authority test：tag 的單一真相在 Wiki 側；人類可控 mutation point 只有
 * REVIEW-stage 且非 REPAIR lineage 的 proposal。
 */
@Tag("unit")
class KnowledgeOrganizationPolicyTest {

    @Test
    void onlyReviewProposalsAreTagEditable() {
        assertThat(KnowledgeOrganizationPolicy.isTagEditableStatus(KnowledgeProposalStatus.REVIEW)).isTrue();
        assertThat(KnowledgeOrganizationPolicy.isTagEditableStatus(KnowledgeProposalStatus.DRAFT)).isFalse();
        assertThat(KnowledgeOrganizationPolicy.isTagEditableStatus(KnowledgeProposalStatus.APPROVED)).isFalse();
        assertThat(KnowledgeOrganizationPolicy.isTagEditableStatus(KnowledgeProposalStatus.REJECTED)).isFalse();
        assertThat(KnowledgeOrganizationPolicy.isTagEditableStatus(null)).isFalse();
    }

    @Test
    void repairLineageIsNeverTagEditable() {
        assertThat(KnowledgeOrganizationPolicy.isTagEditableSourceKind("DOCUMENT_ANALYSIS")).isTrue();
        assertThat(KnowledgeOrganizationPolicy.isTagEditableSourceKind("ASK")).isTrue();
        assertThat(KnowledgeOrganizationPolicy.isTagEditableSourceKind("REPAIR")).isFalse();
        assertThat(KnowledgeOrganizationPolicy.isTagEditableSourceKind("UNKNOWN_FUTURE_KIND")).isFalse();
        assertThat(KnowledgeOrganizationPolicy.isTagEditableSourceKind(null)).isFalse();
    }
}
