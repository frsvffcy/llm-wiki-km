package org.km.llmwiki.graph;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;

/**
 * Application-owned, versioned identity encoding shared by all Graph adapters.
 *
 * <p>Every component is length-prefixed UTF-8 before hashing. This avoids locale, default
 * charset, operating-system, insertion-order, and vendor serialization differences.
 */
public final class GraphIdentityCodec {

    public static final String VERSION = "graph-identity-v1";

    private GraphIdentityCodec() {
    }

    public static String entityStableId(GraphWorkspaceScope workspace, GraphEntityType type,
                                        String canonicalKey) {
        return "ge1_" + digest("entity", Long.toString(requireWorkspace(workspace)),
                requireType(type).name(), requireCanonicalKey(canonicalKey));
    }

    public static String relationStableId(GraphWorkspaceScope workspace,
                                          GraphEntityIdentity source,
                                          GraphRelationType type,
                                          GraphEntityIdentity target) {
        if (workspace == null || source == null || target == null
                || !workspace.equals(source.workspace())
                || !workspace.equals(target.workspace())) {
            throw new IllegalArgumentException("Graph relation identities must share a workspace");
        }
        return "gr1_" + digest("relation", Long.toString(workspace.id()), source.stableId(),
                requireRelationType(type).wireValue(), target.stableId());
    }

    static String digest(String domain, String... components) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, VERSION);
            update(digest, domain);
            for (String component : components) {
                update(digest, component);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        byte[] length = Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII);
        digest.update(length);
        digest.update((byte) ':');
        digest.update(bytes);
        digest.update((byte) ';');
    }

    private static long requireWorkspace(GraphWorkspaceScope workspace) {
        if (workspace == null) {
            throw new IllegalArgumentException("Graph workspace is required");
        }
        return workspace.id();
    }

    private static GraphEntityType requireType(GraphEntityType type) {
        if (type == null) {
            throw new IllegalArgumentException("Graph type is required");
        }
        return type;
    }

    private static GraphRelationType requireRelationType(GraphRelationType type) {
        if (type == null) {
            throw new IllegalArgumentException("Graph relation type is required");
        }
        return type;
    }

    private static String requireCanonicalKey(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Graph canonical identity must not be blank");
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC).trim();
        if (normalized.isBlank()
                || normalized.codePointCount(0, normalized.length())
                > GraphEntityIdentity.MAX_CANONICAL_KEY_CODE_POINTS) {
            throw new IllegalArgumentException("Graph canonical identity is too long");
        }
        return normalized;
    }
}
