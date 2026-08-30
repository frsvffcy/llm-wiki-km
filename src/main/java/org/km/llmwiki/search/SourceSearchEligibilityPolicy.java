package org.km.llmwiki.search;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.List;

/** Shared authority eligibility policy for Source FTS projection and Retrieval revalidation. */
public final class SourceSearchEligibilityPolicy {

    private SourceSearchEligibilityPolicy() {
    }

    public static boolean documentEligible(SourceSearchAuthorityDocument authority) {
        return "PROCESSED".equals(authority.parseStatus())
                && !List.of("DELETED", "SUPERSEDED", "DUPLICATE")
                .contains(authority.documentStatus());
    }

    public static boolean chunkEligible(SourceSearchAuthorityChunk chunk) {
        String content = chunk.normalizedContent();
        return chunk.sourceChunkId() > 0
                && chunk.chunkNo() > 0
                && (chunk.pageNo() == null || chunk.pageNo() > 0)
                && content != null
                && !content.isBlank()
                && Normalizer.isNormalized(content, Normalizer.Form.NFC)
                && sha256(content).equals(chunk.contentHash());
    }

    static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
