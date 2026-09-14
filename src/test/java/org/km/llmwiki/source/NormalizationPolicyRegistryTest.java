package org.km.llmwiki.source;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

@Tag("unit")
class NormalizationPolicyRegistryTest {

    @Test
    void resolvesActivePolicyWithV2ProductionDefault() {
        NormalizationPolicyRegistry registry = registry(NormalizationPolicyV2SelectedCfStrip.VERSION);

        assertThat(registry.activeVersion())
                .isEqualTo(NormalizationPolicyV2SelectedCfStrip.VERSION);
        assertThat(registry.active().version())
                .isEqualTo(NormalizationPolicyV2SelectedCfStrip.VERSION);
    }

    @Test
    void supportsRollbackTargetBaseline() {
        NormalizationPolicyRegistry registry = registry(NormalizationPolicyV1Current.VERSION);

        assertThat(registry.activeVersion()).isEqualTo(NormalizationPolicyV1Current.VERSION);
        assertThat(registry.active().apply("a­b")).isEqualTo("a­b");
    }

    @Test
    void failsFastOnUnknownVersion() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> registry("normalization-policy-vX-unknown"))
                .withMessageContaining("normalization-policy-vX-unknown");
    }

    @Test
    void rejectsDuplicateVersions() {
        NormalizationPolicy first = new NormalizationPolicyV1Current();
        NormalizationPolicy duplicate = new NormalizationPolicyV1Current();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new NormalizationPolicyRegistry(
                        List.of(first, duplicate), NormalizationPolicyV1Current.VERSION))
                .withMessageContaining(NormalizationPolicyV1Current.VERSION);
    }

    @Test
    void rejectsBlankVersions() {
        NormalizationPolicy blank = new NormalizationPolicy() {
            @Override
            public String version() {
                return "  ";
            }

            @Override
            public String apply(String content) {
                return content;
            }
        };
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new NormalizationPolicyRegistry(
                        List.of(blank), "  "));
    }

    private static NormalizationPolicyRegistry registry(String activeVersion) {
        return new NormalizationPolicyRegistry(
                List.of(new NormalizationPolicyV1Current(),
                        new NormalizationPolicyV2SelectedCfStrip()),
                activeVersion);
    }
}
