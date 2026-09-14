package org.km.llmwiki.testsupport;

/**
 * Shared owner credential fixtures for tests (#423).
 *
 * <p>{@link #VERIFIER_600K} is the versioned hardened verifier for
 * {@link #PASSWORD}, generated once with a fixed salt at the production
 * default cost. Parsing it never runs key derivation, so validator and
 * properties tests stay instant; verification tests additionally prove the
 * vector against the production KDF. The value contains {@code $} separators
 * by design: Spring {@code @TestPropertySource} entries take it literally,
 * while shell environment files must single-quote it (see
 * {@code deploy/systemd/owner.env.example}).
 */
public final class OwnerCredentialFixtures {

    private OwnerCredentialFixtures() {
    }

    public static final String PASSWORD = "owner-test-password";

    public static final String VERIFIER_600K =
            "pbkdf2-sha256$v1$iter=600000$MDEyMzQ1Njc4OWFiY2RlZg$OExLF-y530K2YCU_905346sLUKmgzToFNdzTASr1_1Q";

    /** Legacy unsalted SHA-256 of {@link #PASSWORD}; LOCAL_ONLY migration aid only. */
    public static final String LEGACY_HASH =
            "9148a9b37f4f80aa2e47430e455049e2e41c020df0febe085c306c40a2626393";
}
