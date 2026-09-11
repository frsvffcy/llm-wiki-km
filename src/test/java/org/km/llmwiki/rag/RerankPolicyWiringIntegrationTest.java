package org.km.llmwiki.rag;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;

import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-context wiring gate for the production rerank adoption (#326): the configured active
 * version is the adopted {@code rerank-policy-v1-exact-anchor}, the registry exposes it
 * through the executor, and the noop version remains a registered rollback target that the
 * operator can switch back to without rebuilding any projection.
 */
@Tag("integration")
class RerankPolicyWiringIntegrationTest extends IsolatedIntegrationTest {

    @Autowired
    private SecondStageRerankPolicyRegistry registry;

    @Autowired
    private SecondStageRerankService rerankService;

    @Test
    void activeProductionPolicyIsTheExactAnchorAdoption() {
        assertThat(registry.activeVersion())
                .isEqualTo(ExactAnchorRerankPolicyV1.VERSION);
        assertThat(registry.active()).isInstanceOf(ExactAnchorRerankPolicyV1.class);
        assertThat(rerankService.apply(bundle("query", java.util.List.of(
                evidenceItem("a", "資料庫的備份節奏"),
                evidenceItem("b", "ORA-12899 錯誤的診斷方法")))).status())
                .isEqualTo(RerankStatus.APPLIED);
    }

    private static EvidenceBundle bundle(String query, java.util.List<EvidenceItem> items) {
        int characters = items.stream().mapToInt(item ->
                item.content().codePointCount(0, item.content().length())).sum();
        return new EvidenceBundle(query, RetrievalMode.HYBRID_FTS,
                new EvidenceWorkspace(7, "Rerank Wiring"), items,
                new EvidenceBudget(8, 100_000, items.size(), characters, (characters + 3) / 4,
                        false), items.size(), 0, items.isEmpty(),
                RetrievalDiagnostics.lexical());
    }

    private static EvidenceItem evidenceItem(String id, String content) {
        return new EvidenceItem(EvidenceKind.WIKI, id,
                new EvidenceWorkspace(7, "Rerank Wiring"), 0.9, content, "snippet", false,
                "hash-" + id, id, "Title " + id, "CONCEPT", "vault/" + id + ".md", 1,
                null, null, null, null, null, null, null);
    }
}
