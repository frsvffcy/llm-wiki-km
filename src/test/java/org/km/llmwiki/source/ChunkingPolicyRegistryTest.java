package org.km.llmwiki.source;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

@Tag("unit")
class ChunkingPolicyRegistryTest {

    @Test
    void resolvesTheActivePolicyAndFailsFastOnUnknownVersion() {
        ChunkingPolicy v1 = policy(SourceChunker.CHUNK_POLICY_VERSION);
        ChunkingPolicy v2 = policy(HeadingAnchoredChunkingPolicy.CHUNK_POLICY_VERSION);

        ChunkingPolicyRegistry registry =
                new ChunkingPolicyRegistry(List.of(v1, v2), HeadingAnchoredChunkingPolicy.CHUNK_POLICY_VERSION);

        assertThat(registry.active()).isSameAs(v2);
        assertThat(registry.activeVersion()).isEqualTo("chunk-policy-v2-heading-anchor");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChunkingPolicyRegistry(List.of(v1, v2), "chunk-policy-vX-unknown"))
                .withMessageContaining("chunk-policy-vX-unknown");
    }

    @Test
    void duplicatePolicyVersionsAreRejected() {
        assertThatIllegalStateException().isThrownBy(() ->
                new ChunkingPolicyRegistry(
                        List.of(policy("same"), policy("same")), "same"));
    }

    private ChunkingPolicy policy(String version) {
        return new FixedVersionPolicy(new ExtractedContentNormalizer(
                new ExtractedContentNormalizationProperties()), version);
    }

    private record FixedVersionPolicy(ExtractedContentNormalizer normalizer,
                                      String version) implements ChunkingPolicy {
        @Override
        public List<SourceChunkDraft> chunk(ParsedDocument parsed,
                                            ExtractedContentNormalizer.CanonicalNormalization canonicalNormalization) {
            return List.of();
        }
    }
}
