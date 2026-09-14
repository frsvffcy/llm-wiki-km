package org.km.llmwiki.web.security;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;
import java.util.Base64;

/**
 * Versioned, salted, adaptive owner password verifier (#423).
 *
 * <p>The legacy contract was a bare unsalted SHA-256 hex digest, which is a fast
 * digest rather than a password hashing scheme (OWASP Password Storage Cheat
 * Sheet recommends Argon2id, else scrypt/PBKDF2 with a unique salt; NIST SP
 * 800-63B-4 requires a salted, suitable password hashing scheme carrying its
 * scheme/cost metadata). This class replaces it with a dependency-free
 * PBKDF2-HMAC-SHA256 baseline on the current Java 21 runtime: no external
 * crypto dependency and its supply-chain cost, per-verifier {@link SecureRandom}
 * salt, calibratable iteration count, constant-time final comparison, and a
 * versioned format that leaves room for a future Argon2id scheme without
 * another silent reinterpretation.
 *
 * <p>Format (single authority, never contains plaintext):
 * {@code pbkdf2-sha256$v1$iter=<N>$<salt-b64url>$<key-b64url>}
 * with a 16-byte salt and a 256-bit key. Cost metadata travels inside the
 * verifier, so verification never trusts request-controlled parameters.
 */
public final class OwnerPasswordVerifier {

    /** Current scheme identifier. Unknown schemes fail fast, never downgrade. */
    public static final String SCHEME = "pbkdf2-sha256";

    /** Current format version. Unknown versions fail fast, never reinterpret. */
    public static final String VERSION = "v1";

    /** OWASP-aligned default cost (PBKDF2-HMAC-SHA256); see the #423 decision record. */
    public static final int DEFAULT_ITERATIONS = 600_000;

    /** Floor that stays ~10^4x above a fast digest; near-fast-hash costs are rejected. */
    public static final int MIN_ITERATIONS = 210_000;

    /** Ceiling that keeps a single login bounded; cost is config-owned, never request-controlled. */
    public static final int MAX_ITERATIONS = 2_000_000;

    static final int SALT_BYTES = 16;
    static final int KEY_BITS = 256;

    /** Mirrors the login API bound so oversized input fails before key derivation. */
    static final int MAX_PASSWORD_LENGTH = 512;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private OwnerPasswordVerifier() {
    }

    /** Parsed verifier: cost metadata plus raw salt/key, no password material. */
    public record Parsed(int iterations, byte[] salt, byte[] key) {
    }

    /** Creates a verifier for a new password with a fresh random salt. */
    public static String hash(String password, int iterations) {
        checkPassword(password);
        checkIterations(iterations);
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return encode(password, salt, iterations);
    }

    /** Creates a verifier with default cost and a fresh random salt. */
    public static String hash(String password) {
        return hash(password, DEFAULT_ITERATIONS);
    }

    /** Deterministic construction for tests and stable fixtures (explicit salt). */
    static String hashWithSalt(String password, byte[] salt, int iterations) {
        checkPassword(password);
        if (salt == null || salt.length != SALT_BYTES) {
            throw new IllegalArgumentException("Password verifier salt must hold 16 bytes");
        }
        checkIterations(iterations);
        return encode(password, salt.clone(), iterations);
    }

    /**
     * Parses a configured verifier or throws with a fixed operator-safe message.
     * The message never echoes the supplied value or any crypto detail.
     */
    public static Parsed parse(String verifier) {
        if (verifier == null || verifier.isBlank() || !verifier.equals(verifier.strip())) {
            throw new IllegalArgumentException("Owner password verifier is malformed");
        }
        String[] parts = verifier.strip().split("\\$", -1);
        if (parts.length != 5
                || !SCHEME.equals(parts[0])
                || !VERSION.equals(parts[1])
                || !parts[2].startsWith("iter=")) {
            throw new IllegalArgumentException("Owner password verifier is malformed");
        }
        int iterations;
        try {
            iterations = Integer.parseInt(parts[2].substring("iter=".length()));
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException("Owner password verifier is malformed");
        }
        checkIterations(iterations);
        byte[] salt = decode(parts[3], SALT_BYTES);
        byte[] key = decode(parts[4], KEY_BITS / 8);
        if (salt == null || key == null) {
            throw new IllegalArgumentException("Owner password verifier is malformed");
        }
        return new Parsed(iterations, salt, key);
    }

    /**
     * Verifies a candidate against a stored verifier. Any failure — wrong
     * password, malformed verifier, unsupported version — returns {@code false}
     * without leaking which check failed. The final key comparison runs in
     * constant time.
     */
    public static boolean verify(String candidate, String verifier) {
        if (candidate == null || candidate.length() > MAX_PASSWORD_LENGTH) {
            return false;
        }
        Parsed parsed;
        try {
            parsed = parse(verifier);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        byte[] actual = derive(candidate, parsed.salt(), parsed.iterations());
        boolean matches = actual != null && MessageDigest.isEqual(parsed.key(), actual);
        if (actual != null) {
            Arrays.fill(actual, (byte) 0);
        }
        return matches;
    }

    private static String encode(String password, byte[] salt, int iterations) {
        byte[] key = derive(password, salt, iterations);
        if (key == null) {
            throw new IllegalStateException("Password verifier derivation is unavailable");
        }
        String encoded = SCHEME + "$" + VERSION + "$iter=" + iterations
                + "$" + ENCODER.encodeToString(salt)
                + "$" + ENCODER.encodeToString(key);
        Arrays.fill(key, (byte) 0);
        return encoded;
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        char[] chars = password.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(chars, salt, iterations, KEY_BITS);
        Arrays.fill(chars, '\0');
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] key = factory.generateSecret(spec).getEncoded();
            spec.clearPassword();
            return key;
        } catch (NoSuchAlgorithmException | InvalidKeySpecException unavailable) {
            spec.clearPassword();
            return null;
        }
    }

    private static byte[] decode(String text, int expectedLength) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        byte[] raw;
        try {
            raw = DECODER.decode(text);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
        return raw.length == expectedLength ? raw : null;
    }

    private static void checkPassword(String password) {
        if (password == null || password.isEmpty() || password.length() > MAX_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Owner password is not usable for verifier generation");
        }
    }

    private static void checkIterations(int iterations) {
        if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) {
            throw new IllegalArgumentException("Owner password verifier cost is out of range");
        }
    }
}
