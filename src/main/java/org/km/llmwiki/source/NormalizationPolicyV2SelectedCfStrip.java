package org.km.llmwiki.source;

import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Selected-Cf strip policy (#412 production default, GO subset from #380).
 *
 * <p>Strips only the artifact subset proven safe by #380 evaluation:
 * U+00AD SOFT HYPHEN, U+200B ZERO WIDTH SPACE, U+2060 WORD JOINER, and U+FEFF
 * outside the leading position (stray BOM). The leading code point is preserved
 * so a legitimate stream/file BOM keeps existing parser/decoder semantics.
 *
 * <p>Explicitly never strips: U+200C ZWNJ, U+200D ZWJ (shaping/emoji semantics),
 * U+202A–U+202E BiDi controls (#380 DEFER→FLAG), or any other General Category
 * {@code Cf} character. The strip set is an allowlist — not a
 * {@code Character.getType == FORMAT} blanket removal.
 *
 * <p>Deterministic, provider-free and locale-independent (code-point scan only).
 */
@Component
public class NormalizationPolicyV2SelectedCfStrip implements NormalizationPolicy {

    public static final String VERSION = "normalization-policy-v2-selected-cf-strip";

    private static final int SOFT_HYPHEN = 0x00AD;
    private static final int ZERO_WIDTH_SPACE = 0x200B;
    private static final int WORD_JOINER = 0x2060;
    private static final int ZERO_WIDTH_NO_BREAK_SPACE = 0xFEFF;

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public String apply(String content) {
        Objects.requireNonNull(content, "content must not be null");
        StringBuilder stripped = new StringBuilder(content.length());
        int[] codePoints = content.codePoints().toArray();
        for (int index = 0; index < codePoints.length; index++) {
            int codePoint = codePoints[index];
            if (isSelectedArtifact(codePoint, index)) {
                continue;
            }
            stripped.appendCodePoint(codePoint);
        }
        return stripped.toString();
    }

    private static boolean isSelectedArtifact(int codePoint, int codePointIndex) {
        if (codePoint == SOFT_HYPHEN
                || codePoint == ZERO_WIDTH_SPACE
                || codePoint == WORD_JOINER) {
            return true;
        }
        // Stray BOM only: the leading code point keeps decoder-boundary semantics.
        return codePoint == ZERO_WIDTH_NO_BREAK_SPACE && codePointIndex != 0;
    }
}
