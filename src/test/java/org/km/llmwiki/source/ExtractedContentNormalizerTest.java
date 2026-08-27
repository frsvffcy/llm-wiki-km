package org.km.llmwiki.source;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractedContentNormalizerTest {

    @Test
    void normalizesLineEndingsAndUnicodeToNfc() {
        assertThat(normalizer().normalize("caf\u0065\u0301\r\nsecond\rthird"))
                .isEqualTo("caf\u00e9\nsecond\nthird");
    }

    @Test
    void removesControlCharactersAndExcessBlankOrTrailingWhitespaceWithoutChangingCodeIndentation() {
        String content = "    if (enabled) {\t \n\u0000\t  run();  \n    }\n\n\n\n";

        assertThat(normalizer().normalize(content))
                .isEqualTo("    if (enabled) {\n\t  run();\n    }\n\n");
    }

    @Test
    void removesRepeatedPageHeadersAndFootersWhenEnabled() {
        String content = "Monthly report\nFirst page\nConfidential\f"
                + "Monthly report\nSecond page\nConfidential";

        assertThat(normalizer().normalize(content)).isEqualTo("First page\fSecond page");
    }

    @Test
    void keepsRepeatedPageHeadersAndFootersWhenStrategyIsDisabled() {
        ExtractedContentNormalizationProperties properties = new ExtractedContentNormalizationProperties();
        properties.setRepeatedHeaderFooterEnabled(false);
        ExtractedContentNormalizer normalizer = new ExtractedContentNormalizer(properties);
        String content = "Monthly report\nFirst page\nConfidential\f"
                + "Monthly report\nSecond page\nConfidential";

        assertThat(normalizer.normalize(content)).isEqualTo(content);
    }

    @Test
    void respectsConfiguredMinimumOccurrencesForRepeatedHeadersAndFooters() {
        ExtractedContentNormalizationProperties properties = new ExtractedContentNormalizationProperties();
        properties.setRepeatedHeaderFooterMinimumOccurrences(3);
        ExtractedContentNormalizer normalizer = new ExtractedContentNormalizer(properties);
        String content = "Monthly report\nFirst page\nConfidential\f"
                + "Monthly report\nSecond page\nConfidential";

        assertThat(normalizer.normalize(content)).isEqualTo(content);
    }

    @Test
    void producesTheSameOutputForTheSameInput() {
        String content = "Header\r\nBody\n\n\nFooter\fHeader\nNext\nFooter";

        assertThat(normalizer().normalize(content))
                .isEqualTo(normalizer().normalize(content));
    }

    private static ExtractedContentNormalizer normalizer() {
        return new ExtractedContentNormalizer(new ExtractedContentNormalizationProperties());
    }
}
