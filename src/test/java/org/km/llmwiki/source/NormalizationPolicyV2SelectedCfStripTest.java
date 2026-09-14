package org.km.llmwiki.source;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class NormalizationPolicyV2SelectedCfStripTest {

    private final NormalizationPolicy policy = new NormalizationPolicyV2SelectedCfStrip();

    @Test
    void reportsSelectedCfStripVersion() {
        assertThat(policy.version()).isEqualTo("normalization-policy-v2-selected-cf-strip");
    }

    @Test
    void stripsSoftHyphenZeroWidthSpaceAndWordJoiner() {
        assertThat(policy.apply("self­attention")).isEqualTo("selfattention");
        assertThat(policy.apply("key​word")).isEqualTo("keyword");
        assertThat(policy.apply("a⁠b")).isEqualTo("ab");
        assertThat(policy.apply("知​識管理")).isEqualTo("知識管理");
    }

    @Test
    void stripsOnlyNonLeadingBomAndKeepsLeadingBomForDecoderSemantics() {
        assertThat(policy.apply("﻿前言")).isEqualTo("﻿前言");
        assertThat(policy.apply("前﻿言")).isEqualTo("前言");
        assertThat(policy.apply("a﻿b﻿c")).isEqualTo("abc");
    }

    @Test
    void keepsZeroWidthJoinerNonJoinerAndBidiControls() {
        String zwjEmoji = "👨‍💻";
        assertThat(policy.apply(zwjEmoji)).isEqualTo(zwjEmoji);
        String zwnj = "می‌ Hammond";
        assertThat(policy.apply("می‌شود")).isEqualTo("می‌شود");
        assertThat(policy.apply(zwnj)).isEqualTo(zwnj);
        String bidi = "abc‮def";
        assertThat(policy.apply(bidi)).isEqualTo(bidi);
        for (String control : new String[]{"‪", "‫", "‬", "‭", "‮"}) {
            assertThat(policy.apply("a" + control + "b")).isEqualTo("a" + control + "b");
        }
    }

    @Test
    void isDeterministicAndPreservesUnrelatedContent() {
        String content = "Café 報告 # Overview\n\nFirst paragraph.\n";
        assertThat(policy.apply(content)).isEqualTo(policy.apply(content));
        assertThat(policy.apply(content)).isEqualTo(content);
        assertThat(policy.apply("")).isEmpty();
    }
}
