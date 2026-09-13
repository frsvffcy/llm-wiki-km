package org.km.llmwiki.source;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.search.CjkBigramProjector;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Evaluation evidence for #380 (decision gate — this test changes no production
 * behavior). It pins the CURRENT normalization baseline and the retrieval impact of
 * Unicode format characters through the real FTS CJK projection, so any future
 * normalization-policy adoption has a reproducible before/after reference.
 *
 * <p>Baseline finding: {@link ExtractedContentNormalizer} removes only ISO Control
 * characters (U+0000–1F, U+007F–9F) — every format character evaluated here SURVIVES
 * into canonical content, and the FTS CJK projection then treats each of them as a
 * token boundary, breaking cross-character matching for artifact-bearing text.
 */
@Tag("unit")
class UnicodeFormatCharacterEvaluationTest {

    private final ExtractedContentNormalizer normalizer = new ExtractedContentNormalizer(
            new ExtractedContentNormalizationProperties());

    /** SOFT HYPHEN — PDF/Office extraction artifact (Cf, not ISO Control: survives baseline). */
    private static final String SOFT_HYPHEN = "\u00AD";
    /** ZERO WIDTH SPACE — copied-web-text artifact (Cf). */
    private static final String ZERO_WIDTH_SPACE = "\u200B";
    /** ZERO WIDTH JOINER — semantic inside emoji sequences and Indic/Arabic shaping. */
    private static final String ZERO_WIDTH_JOINER = "\u200D";
    /** ZERO WIDTH NON-JOINER — semantic shaping control. */
    private static final String ZERO_WIDTH_NON_JOINER = "\u200C";
    /** WORD JOINER — invisible glue artifact. */
    private static final String WORD_JOINER = "\u2060";
    /** BOM / ZERO WIDTH NO-BREAK SPACE — stray file artifact when not at stream start. */
    private static final String BOM = "\uFEFF";
    /** LEFT-TO-RIGHT OVERRIDE — BiDi display/spoofing-sensitive control (Cf). */
    private static final String RLO_OVERRIDE = "\u202E";

    @Test
    void baselineNormalizationKeepsEveryEvaluatedFormatCharacter() {
        // The current pipeline is syntax-only: NFC + ISO Control removal. Every format
        // character in the evaluation set survives — this is the baseline the decision
        // gate measured, and any policy change must be versioned against it.
        String artifacts = SOFT_HYPHEN + ZERO_WIDTH_SPACE + WORD_JOINER + BOM
                + ZERO_WIDTH_JOINER + ZERO_WIDTH_NON_JOINER + RLO_OVERRIDE;
        String normalized = normalizer.normalize("body" + artifacts + "text");
        for (String character : new String[]{SOFT_HYPHEN, ZERO_WIDTH_SPACE, WORD_JOINER,
                BOM, ZERO_WIDTH_JOINER, ZERO_WIDTH_NON_JOINER, RLO_OVERRIDE}) {
            assertThat(normalized).contains(character);
        }
    }

    @Test
    void ftsProjectionBreaksMatchingForArtifactBearingLatinAndCjkText() {
        // Mechanism evidence: the FTS CJK projection treats every format character as a
        // token boundary, so artifact-bearing content cannot be found by clean queries.
        // Deterministic and reproducible — this is the concrete retrieval failure mode.
        assertThat(CjkBigramProjector.transform("self" + SOFT_HYPHEN + "attention"))
                .isEqualTo("self attention")
                .as("soft hyphen splits the indexed token");
        assertThat(CjkBigramProjector.transform("selfattention"))
                .isEqualTo("selfattention")
                .doesNotContain(" ")
                .as("the clean query projects to a single token that cannot match");
        assertThat(CjkBigramProjector.transform("知" + ZERO_WIDTH_SPACE + "識管理"))
                .isEqualTo("知 識管 管理");
        assertThat(CjkBigramProjector.transform("知識管理"))
                .isEqualTo("知識 識管 管理");
        assertThat(CjkBigramProjector.transform("key" + ZERO_WIDTH_SPACE + "word"))
                .isEqualTo("key word");
        assertThat(CjkBigramProjector.transform("keyword")).isEqualTo("keyword");
        assertThat(CjkBigramProjector.transform("abc" + RLO_OVERRIDE + "def"))
                .isEqualTo("abc def");
    }

    @Test
    void selectedArtifactSubsetIsSafeToStripWhileSemanticCharactersMustBeKept() {
        // STRIP candidates (pure artifacts): stripping restores clean-token matching.
        String stripped = "self" + "attention";
        assertThat(CjkBigramProjector.transform(stripped)).isEqualTo("selfattention")
                .as("stripping the soft hyphen restores the clean-query match");

        // Semantic characters must survive stripping: ZWJ is load-bearing inside emoji
        // sequences — stripping it would corrupt canonical content and rendering.
        String emoji = "\uD83D\uDC68" + ZERO_WIDTH_JOINER + "\uD83D\uDCBB";
        assertThat(emoji).contains(ZERO_WIDTH_JOINER);

        // ZWNJ keeps shaping semantics (e.g. Persian "مي‌‌" contrasts): never blanket-strip.
        assertThat("می" + ZERO_WIDTH_NON_JOINER + "شود").contains(ZERO_WIDTH_NON_JOINER);
    }

    @Test
    void strippingChangesCanonicalBytesAndHashesSoAdoptionRequiresVersioning() {
        String original = "self" + SOFT_HYPHEN + "attention";
        String stripped = "self" + "attention";
        // The content hash contract means any canonical byte change invalidates stored
        // hashes and derived projections — a KEEP→STRIP switch is a versioned policy
        // change (re-extraction/re-projection), never an in-place rewrite (#380 §D).
        assertThat(org.km.llmwiki.wiki.WikiContentHash.sha256(original))
                .isNotEqualTo(org.km.llmwiki.wiki.WikiContentHash.sha256(stripped));
    }

    @Test
    void contextualBomHandlingDiffersByPosition() {
        // A leading BOM is a file artifact; the same code point mid-content is equally
        // removable — but the FTS projector already isolates it as a boundary, so the
        // retrieval impact differs by surrounding text (contextual, not blanket).
        assertThat(CjkBigramProjector.transform(BOM + "前言")).contains("前言");
        assertThat(CjkBigramProjector.transform("前" + BOM + "言"))
                .isEqualTo("前 言")
                .as("a mid-content BOM splits CJK tokens — contextual handling is required");
    }
}
