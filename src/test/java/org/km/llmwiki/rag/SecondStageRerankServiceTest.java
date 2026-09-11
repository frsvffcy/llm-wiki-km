package org.km.llmwiki.rag;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.rag.SecondStageRerankPolicy.NoOp;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Holds the second-stage rerank execution boundary (#326): the policy can only reorder the
 * already-qualified evidence, the blocking identity invariants are revalidated against every
 * policy output, a defective policy deterministically falls back to the baseline order, and the
 * versioned registry fails fast on unknown/duplicate/blank versions.
 */
@Tag("unit")
class SecondStageRerankServiceTest {

    private static final org.km.llmwiki.rag.EvidenceWorkspace WORKSPACE =
            new org.km.llmwiki.rag.EvidenceWorkspace(7, "Rerank");

    private final SecondStageRerankService service = new SecondStageRerankService(
            new SecondStageRerankPolicyRegistry(
                    List.of(new SecondStageRerankPolicy.NoOp(),
                            new ExactAnchorRerankPolicyV1()),
                    ExactAnchorRerankPolicyV1.VERSION));

    @Test
    void noopPolicyIsTheStableRollbackTarget() {
        SecondStageRerankPolicy.NoOp policy = new SecondStageRerankPolicy.NoOp();
        EvidenceBundle bundle = bundle("query", List.of(item("a", "first"),
                item("b", "second")));

        RerankResult result = policy.apply(bundle);

        assertThat(result.status()).isEqualTo(RerankStatus.APPLIED);
        assertThat(result.orderedItems()).isSameAs(bundle.items());
        assertThat(result.policyVersion()).isEqualTo("rerank-policy-v1-noop");
        assertThat(new SecondStageRerankPolicy.NoOp().version())
                .isEqualTo("rerank-policy-v1-noop");
    }

    @Test
    void insufficientCandidatesAreTypedNoOps() {
        SecondStageRerankPolicy.NoOp policy = new SecondStageRerankPolicy.NoOp();
        EvidenceBundle single = bundle("query", List.of(item("a", "only evidence")));
        EvidenceBundle empty = bundle("query", List.of());

        assertThat(policy.apply(single).status())
                .isEqualTo(RerankStatus.NO_OP_INSUFFICIENT_CANDIDATES);
        assertThat(policy.apply(single).noOpReason())
                .isEqualTo(RerankNoOpReason.INSUFFICIENT_CANDIDATES);
        assertThat(policy.apply(empty).status())
                .isEqualTo(RerankStatus.NO_OP_INSUFFICIENT_CANDIDATES);
    }

    @Test
    void exactAnchorReordersWithinTheIdenticalIdentitySet() {
        EvidenceBundle bundle = bundle("ORA-12899 lock", List.of(
                item("backup", "資料庫的備份節奏與異地副本"),
                item("ora", "寫入資料庫超出欄位長度時會出現 ORA-12899 錯誤")));

        RerankResult result = service.apply(bundle);

        assertThat(result.status()).isEqualTo(RerankStatus.APPLIED);
        assertThat(result.policyVersion()).isEqualTo("rerank-policy-v1-exact-anchor");
        assertThat(result.orderedItems().stream().map(item -> item.stableId().strip()).toList())
                .containsExactly("ora", "backup");
        // The identity set is unchanged: reorder only, never resurrect or drop.
        assertThat(result.orderedItems().stream().map(item -> item.stableIdentity())
                .sorted(java.util.Comparator.naturalOrder()).toList())
                .containsExactlyElementsOf(bundle.items().stream()
                        .map(item -> item.stableIdentity())
                        .sorted(java.util.Comparator.naturalOrder()).toList());
    }

    @Test
    void defectivePoliciesFallBackToTheBaselineOrder() {
        EvidenceBundle bundle = bundle("query", List.of(item("a", "alpha"), item("b", "beta")));
        SecondStageRerankService guarded = new SecondStageRerankService(registryWithPolicy(
                new SecondStageRerankPolicy() {
                    @Override
                    public String version() {
                        return "test-hostile";
                    }

                    @Override
                    public RerankResult apply(EvidenceBundle evidence) {
                        // Drops evidence: the identity invariant must catch this.
                        return new RerankResult(
                                bundle("query", List.of(item("a", "alpha"))),
                                RerankStatus.APPLIED, null, "test-hostile");
                    }
                }));

        RerankResult result = guarded.apply(bundle("query",
                List.of(item("a", "alpha"), item("b", "beta"))));

        assertThat(result.status()).isEqualTo(RerankStatus.NO_OP_UNSUPPORTED_SHAPE);
        assertThat(result.noOpReason()).isEqualTo(RerankNoOpReason.UNSUPPORTED_QUERY_SHAPE);
        assertThat(result.orderedBundle().items()).hasSize(2);
    }

    @Test
    void policyRuntimeFailuresFallBackToTheBaselineOrder() {
        EvidenceBundle bundle = bundle("query", List.of(item("a", "alpha"), item("b", "beta")));
        SecondStageRerankService guarded = new SecondStageRerankService(registryWithPolicy(
                new SecondStageRerankPolicy() {
                    @Override
                    public String version() {
                        return "test-throws";
                    }

                    @Override
                    public RerankResult apply(EvidenceBundle evidence) {
                        throw new IllegalStateException("hostile rerank defect: secret-token");
                    }
                }));

        RerankResult result = guarded.apply(bundle);

        assertThat(result.status()).isEqualTo(RerankStatus.NO_OP_UNSUPPORTED_SHAPE);
        assertThat(result.orderedItems()).hasSize(2);
    }

    @Test
    void rerankResultRecordValidatesTypedNoopPairing() {
        EvidenceBundle bundle = bundle("query", List.of(item("a", "alpha")));
        assertThatThrownBy(() -> new RerankResult(bundle, RerankStatus.APPLIED,
                RerankNoOpReason.INSUFFICIENT_CANDIDATES, "v"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RerankResult(bundle,
                RerankStatus.NO_OP_UNSUPPORTED_SHAPE, null, "v"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void registryFailsFastOnUnknownDuplicateAndBlankVersions() {
        assertThatThrownBy(() -> new SecondStageRerankPolicyRegistry(
                List.of(new SecondStageRerankPolicy.NoOp()), "rerank-policy-v9-unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知的 rerank policy version");
        assertThatThrownBy(() -> new SecondStageRerankPolicyRegistry(
                List.of(new SecondStageRerankPolicy.NoOp(), new SecondStageRerankPolicy.NoOp()),
                SecondStageRerankPolicy.NoOp.VERSION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重複的 rerank policy version");
        assertThatThrownBy(() -> new SecondStageRerankPolicyRegistry(
                List.of(blankVersionPolicy()), SecondStageRerankPolicy.NoOp.VERSION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rerank policy version must not be blank");
    }

    private SecondStageRerankPolicyRegistry registryWithPolicy(SecondStageRerankPolicy policy) {
        return new SecondStageRerankPolicyRegistry(
                List.of(new SecondStageRerankPolicy.NoOp(), policy),
                policy.version());
    }

    private SecondStageRerankPolicy blankVersionPolicy() {
        return new SecondStageRerankPolicy() {
            @Override
            public String version() {
                return " ";
            }

            @Override
            public RerankResult apply(EvidenceBundle evidence) {
                throw new AssertionError("must not be resolved");
            }
        };
    }

    private static EvidenceBundle bundle(String query, List<EvidenceItem> items) {
        int characters = items.stream().mapToInt(item ->
                item.content().codePointCount(0, item.content().length())).sum();
        return new EvidenceBundle(query, RetrievalMode.HYBRID_FTS, WORKSPACE, items,
                new EvidenceBudget(8, 100_000, items.size(), characters, (characters + 3) / 4,
                        false), items.size(), 0, items.isEmpty(),
                RetrievalDiagnostics.lexical());
    }

    private static EvidenceItem item(String id, String content) {
        return new EvidenceItem(EvidenceKind.WIKI, id, WORKSPACE, 0.9, content, "snippet", false,
                "hash-" + id, id, "Title " + id, "CONCEPT", "vault/" + id + ".md", 1,
                null, null, null, null, null, null, null);
    }
}
