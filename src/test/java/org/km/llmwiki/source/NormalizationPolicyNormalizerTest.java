package org.km.llmwiki.source;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.search.CjkBigramProjector;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class NormalizationPolicyNormalizerTest {

    @Test
    void v2SelectedStripRestoresCleanQueryMatchingThroughProductionNormalizer() {
        ExtractedContentNormalizer normalizer = normalizer(
                NormalizationPolicyV2SelectedCfStrip.VERSION);

        assertThat(CjkBigramProjector.transform(normalizer.normalize("self­attention")))
                .isEqualTo(CjkBigramProjector.transform("selfattention"));
        assertThat(CjkBigramProjector.transform(normalizer.normalize("key​word")))
                .isEqualTo(CjkBigramProjector.transform("keyword"));
        assertThat(CjkBigramProjector.transform(normalizer.normalize("知​識管理")))
                .isEqualTo(CjkBigramProjector.transform("知識管理"));
    }

    @Test
    void v2KeepsSemanticCharactersAndBidiThroughProductionNormalizer() {
        ExtractedContentNormalizer normalizer = normalizer(
                NormalizationPolicyV2SelectedCfStrip.VERSION);
        String zwjEmoji = "👨‍💻";

        assertThat(normalizer.normalize(zwjEmoji)).isEqualTo(zwjEmoji);
        assertThat(normalizer.normalize("می‌شود")).isEqualTo("می‌شود");
        assertThat(normalizer.normalize("abc‮def")).isEqualTo("abc‮def");
    }

    @Test
    void v2HandlesBomContextuallyThroughProductionNormalizer() {
        ExtractedContentNormalizer normalizer = normalizer(
                NormalizationPolicyV2SelectedCfStrip.VERSION);

        assertThat(normalizer.normalize("﻿前言")).isEqualTo("﻿前言");
        assertThat(normalizer.normalize("前﻿言")).isEqualTo("前言");
    }

    @Test
    void v1BaselinePreservesEveryEvaluatedFormatCharacter() {
        ExtractedContentNormalizer normalizer = normalizer(
                NormalizationPolicyV1Current.VERSION);

        String artifacts = "­​⁠﻿‍‌‮";
        assertThat(normalizer.normalize("body" + artifacts + "text"))
                .isEqualTo("body" + artifacts + "text");
    }

    @Test
    void chunkNormalizationSharesDocumentRepeatedEdgeDecisionUnderV2() {
        ExtractedContentNormalizer normalizer = normalizer(
                NormalizationPolicyV2SelectedCfStrip.VERSION);
        String content = "Monthly report\nFirst key​word page\nConfidential\f"
                + "Monthly report\nSecond self­attention page\nConfidential";

        ExtractedContentNormalizer.CanonicalNormalization canonical =
                normalizer.canonicalize(content);

        assertThat(canonical.content()).isEqualTo("First keyword page\fSecond selfattention page");
        assertThat(normalizer.normalizeChunk("First key​word page", canonical))
                .isEqualTo("First keyword page");
        assertThat(normalizer.activeNormalizationVersion())
                .isEqualTo(NormalizationPolicyV2SelectedCfStrip.VERSION);
    }

    private static ExtractedContentNormalizer normalizer(String activeVersion) {
        return new ExtractedContentNormalizer(new ExtractedContentNormalizationProperties(),
                new NormalizationPolicyRegistry(
                        List.of(new NormalizationPolicyV1Current(),
                                new NormalizationPolicyV2SelectedCfStrip()),
                        activeVersion));
    }
}
