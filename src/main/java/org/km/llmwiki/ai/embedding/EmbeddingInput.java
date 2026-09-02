package org.km.llmwiki.ai.embedding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** One bounded canonical text input and its deterministic content identity. */
public record EmbeddingInput(String text, String identity) {

    public static final int MAX_TEXT_CODE_POINTS = 16_000;
    private static final String IDENTITY_ALGORITHM = "SHA-256";

    public EmbeddingInput {
        text = requireText(text);
        String expectedIdentity = identityFor(text);
        if (identity == null || !identity.equals(expectedIdentity)) {
            throw new IllegalArgumentException("identity must be the SHA-256 identity of text");
        }
    }

    public EmbeddingInput(String text) {
        this(text, identityFor(text));
    }

    /** Identity is SHA-256 over the exact UTF-8 canonical text, encoded as lowercase hex. */
    public static String identityFor(String text) {
        String bounded = requireText(text);
        try {
            byte[] digest = MessageDigest.getInstance(IDENTITY_ALGORITHM)
                    .digest(bounded.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("embedding text must not be blank");
        }
        if (value.codePointCount(0, value.length()) > MAX_TEXT_CODE_POINTS) {
            throw new IllegalArgumentException("embedding text exceeds the bounded input size");
        }
        return value;
    }
}
