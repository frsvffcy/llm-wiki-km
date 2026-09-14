package org.km.llmwiki.ai.query;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

@Tag("unit")
class QueryTransformationPolicyRegistryTest {

    @Test
    void selectsEnabledPolicyAndDisabledRollbackTargetByVersion() {
        var disabled = new DisabledQueryTransformationPolicy();
        var enabled = new SingleRewritePolicyV1();

        assertThat(new QueryTransformationPolicyRegistry(List.of(disabled, enabled),
                SingleRewritePolicyV1.VERSION).active()).isSameAs(enabled);
        assertThat(new QueryTransformationPolicyRegistry(List.of(disabled, enabled),
                DisabledQueryTransformationPolicy.VERSION).active()).isSameAs(disabled);
    }

    @Test
    void failsFastForBlankDuplicateAndUnknownVersions() {
        QueryTransformationPolicy blank = policy(" ");

        assertThatIllegalArgumentException().isThrownBy(() ->
                new QueryTransformationPolicyRegistry(List.of(blank), " "));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new QueryTransformationPolicyRegistry(List.of(policy("same"), policy("same")),
                        "same"));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new QueryTransformationPolicyRegistry(List.of(policy("known")), "unknown"));
    }

    @Test
    void registryHasNoPersistenceOrProjectionRebuildDependency() {
        assertThat(QueryTransformationPolicyRegistry.class.getDeclaredFields())
                .extracting(field -> field.getType().getName())
                .allMatch(name -> name.startsWith("java.lang") || name.startsWith("java.util"));
    }

    private static QueryTransformationPolicy policy(String version) {
        return new QueryTransformationPolicy() {
            @Override public String version() { return version; }
            @Override public boolean enabled() { return true; }
            @Override public QueryTransformationApplicability applicability(
                    String query, org.km.llmwiki.rag.EvidenceBundle evidence) {
                return QueryTransformationApplicability.LEXICAL_MISS_CROWD_OUT;
            }
        };
    }
}
