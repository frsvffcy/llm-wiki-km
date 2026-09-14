package org.km.llmwiki.web.security;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.testsupport.OwnerCredentialFixtures;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class OwnerPasswordVerifierTest {

    private static final byte[] FIXED_SALT = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};

    @Test
    void sharedFixtureVerifiesAgainstTheProductionKdf() {
        // Cross-checks the reviewed test vector (generated out-of-band) against
        // the production derivation: a JCA mismatch would fail here, not in CI.
        assertThat(OwnerPasswordVerifier.verify(
                        OwnerCredentialFixtures.PASSWORD, OwnerCredentialFixtures.VERIFIER_600K))
                .isTrue();
        assertThat(OwnerPasswordVerifier.verify(
                        "not-the-password", OwnerCredentialFixtures.VERIFIER_600K))
                .isFalse();
    }

    @Test
    void samePasswordYieldsDifferentVerifiersThatAllVerify() {
        String first = OwnerPasswordVerifier.hash("a fresh operator password");
        String second = OwnerPasswordVerifier.hash("a fresh operator password");

        assertThat(first).isNotEqualTo(second);
        assertThat(OwnerPasswordVerifier.verify("a fresh operator password", first)).isTrue();
        assertThat(OwnerPasswordVerifier.verify("a fresh operator password", second)).isTrue();
        assertThat(OwnerPasswordVerifier.verify("another password", first)).isFalse();
    }

    @Test
    void deterministicHelperSupportsStableFixtures() {
        String verifier = OwnerPasswordVerifier.hashWithSalt(
                "fixture", FIXED_SALT, OwnerPasswordVerifier.MIN_ITERATIONS);

        assertThat(OwnerPasswordVerifier.verify("fixture", verifier)).isTrue();
        assertThat(OwnerPasswordVerifier.verify("Fixture", verifier)).isFalse();
    }

    @Test
    void tamperedSaltCostOrKeyFailClosed() {
        String verifier = OwnerPasswordVerifier.hashWithSalt(
                "fixture", FIXED_SALT, OwnerPasswordVerifier.MIN_ITERATIONS);
        OwnerPasswordVerifier.Parsed parsed = OwnerPasswordVerifier.parse(verifier);

        assertThat(parsed.iterations()).isEqualTo(OwnerPasswordVerifier.MIN_ITERATIONS);
        assertThat(parsed.salt()).hasSize(16);
        assertThat(parsed.key()).hasSize(32);

        String[] parts = verifier.split("\\$", -1);
        // Modified salt (same length, different final character) still parses
        // but no longer verifies.
        String salt = parts[3];
        char flipped = salt.charAt(salt.length() - 1) == 'A' ? 'B' : 'A';
        String tamperedSalt = salt.substring(0, salt.length() - 1) + flipped;
        assertThat(OwnerPasswordVerifier.verify("fixture",
                        parts[0] + "$" + parts[1] + "$" + parts[2]
                                + "$" + tamperedSalt + "$" + parts[4]))
                .isFalse();
        // Truncated verifier.
        assertThat(OwnerPasswordVerifier.verify("fixture",
                        parts[0] + "$" + parts[1] + "$" + parts[2] + "$" + parts[3]))
                .isFalse();
        // Modified cost still parses when in range but no longer verifies.
        String relabeled = parts[0] + "$" + parts[1] + "$iter="
                + (OwnerPasswordVerifier.MIN_ITERATIONS + 1000)
                + "$" + parts[3] + "$" + parts[4];
        assertThat(OwnerPasswordVerifier.verify("fixture", relabeled)).isFalse();
    }

    @Test
    void malformedAndUnknownVerifiersFailFastWithoutEcho() {
        String valid = OwnerPasswordVerifier.hashWithSalt(
                "fixture", FIXED_SALT, OwnerPasswordVerifier.MIN_ITERATIONS);
        String[] parts = valid.split("\\$", -1);
        String tamperedCost = parts[0] + "$" + parts[1] + "$iter=1000"
                + "$" + parts[3] + "$" + parts[4];

        for (String malformed : new String[]{
                null, "", "  ", "not-a-verifier",
                "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
                "argon2id$v1$iter=3$m=65536,abcdef$key",
                "pbkdf2-sha256$v9$iter=210000$" + parts[3] + "$" + parts[4],
                "pbkdf2-sha256$v1$iter=1000$" + parts[3] + "$" + parts[4],
                "pbkdf2-sha256$v1$iter=99999999$" + parts[3] + "$" + parts[4],
                "pbkdf2-sha256$v1$iter=abc$" + parts[3] + "$" + parts[4],
                "pbkdf2-sha256$v1$iter=210000$short$" + parts[4],
                "pbkdf2-sha256$v1$iter=210000$" + parts[3] + "$short",
                tamperedCost,
                " " + valid,
                valid + " "}) {
            assertThatThrownBy(() -> OwnerPasswordVerifier.parse(malformed))
                    .as("malformed verifier must fail fast")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("verifier");
            // Login-time verification fails closed instead of throwing.
            assertThat(OwnerPasswordVerifier.verify("fixture", malformed)).isFalse();
        }
    }

    @Test
    void costBoundsRejectNearFastHashAndUnboundedWork() {
        assertThatThrownBy(() -> OwnerPasswordVerifier.hash("fixture", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OwnerPasswordVerifier.hash("fixture", Integer.MAX_VALUE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(OwnerPasswordVerifier.MIN_ITERATIONS).isGreaterThanOrEqualTo(100_000);
    }

    @Test
    void oversizedCandidatesFailClosed() {
        String verifier = OwnerPasswordVerifier.hashWithSalt(
                "fixture", FIXED_SALT, OwnerPasswordVerifier.MIN_ITERATIONS);

        assertThat(OwnerPasswordVerifier.verify("x".repeat(513), verifier)).isFalse();
        assertThat(OwnerPasswordVerifier.verify(null, verifier)).isFalse();
    }

    @Test
    void formatCarriesNoPlaintext() {
        String verifier = OwnerPasswordVerifier.hash("s3cret-password");

        assertThat(verifier).doesNotContain("s3cret-password");
        assertThat(verifier.split("\\$", -1)).hasSize(5);
        Set<String> distinct = new HashSet<>();
        distinct.add(OwnerPasswordVerifier.hash("s3cret-password"));
        distinct.add(OwnerPasswordVerifier.hash("s3cret-password"));
        assertThat(distinct).hasSize(2);
    }
}
