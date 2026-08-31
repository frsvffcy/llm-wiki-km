package org.km.llmwiki.search;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Deterministic Source authority snapshot shared by indexing, serving and retrieval. */
public final class SourceSearchFreshness {

    private SourceSearchFreshness() {
    }

    public static List<SourceSearchDocument> eligibleDocuments(
            SourceSearchAuthorityDocument authority) {
        if (!SourceSearchEligibilityPolicy.documentEligible(authority)) {
            return List.of();
        }
        List<SourceSearchDocument> result = new ArrayList<>();
        for (SourceSearchAuthorityChunk chunk : authority.chunks()) {
            if (!SourceSearchEligibilityPolicy.chunkEligible(chunk)) {
                continue;
            }
            result.add(new SourceSearchDocument(authority.workspaceId(), chunk.sourceChunkId(),
                    authority.documentId(), chunk.chunkNo(), chunk.pageNo(),
                    chunk.normalizedContent(), chunk.section(), chunk.headingPath(),
                    chunk.contentHash()));
        }
        return List.copyOf(result);
    }

    public static String fingerprint(SourceSearchAuthorityDocument authority) {
        return fingerprint(eligibleDocuments(authority));
    }

    static String fingerprint(List<SourceSearchDocument> documents) {
        MessageDigest digest = sha256Digest();
        for (SourceSearchDocument document : documents) {
            update(digest, Long.toString(document.sourceChunkId()));
            update(digest, Integer.toString(document.chunkNo()));
            update(digest, document.pageNo() == null ? "" : document.pageNo().toString());
            update(digest, document.section());
            update(digest, document.headingPath());
            update(digest, document.normalizedContent());
            update(digest, document.contentHash());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
        digest.update((byte) ';');
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
