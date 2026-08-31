package org.km.llmwiki.wiki;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("contract")
class WikiTargetReferenceTest {

    @Test
    void parsesOnlyExactStableIdentityOrNormalizedCanonicalTitle() {
        assertThat(WikiTargetReference.parse(" wiki:deployment-runbook "))
                .isEqualTo(new WikiTargetReference(
                        WikiTargetReference.Kind.STABLE_IDENTIFIER, "deployment-runbook"));
        assertThat(WikiTargetReference.parse("  Spring   Boot 3  "))
                .isEqualTo(new WikiTargetReference(
                        WikiTargetReference.Kind.CANONICAL_TITLE, "spring boot 3"));
    }

    @Test
    void rejectsBlankMalformedIdentityAndFilesystemLikeReferences() {
        assertInvalid(" ");
        assertInvalid("wiki:");
        assertInvalid("wiki:invalid id");
        assertInvalid("../outside.md");
        assertInvalid("vault/concepts/page.md");
        assertInvalid("line one\nline two");
    }

    private static void assertInvalid(String reference) {
        assertThatThrownBy(() -> WikiTargetReference.parse(reference))
                .isInstanceOf(WikiTargetResolutionException.class)
                .extracting(error -> ((WikiTargetResolutionException) error).reason())
                .isEqualTo(WikiTargetResolutionException.Reason.INVALID_TARGET_REFERENCE);
    }
}
