package org.km.llmwiki.graph;

import java.text.Normalizer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Bounded, deterministic string metadata for a graph projection.
 *
 * <p>This is intentionally not a generic object property bag. Values are copied, NFC-normalized,
 * sorted by key, and bounded before they cross the domain/adapter boundary.
 */
public record GraphMetadata(Map<String, String> entries) {

    public static final int MAX_ENTRIES = 32;
    public static final int MAX_KEY_CODE_POINTS = 64;
    public static final int MAX_VALUE_CODE_POINTS = 512;
    public static final int MAX_TOTAL_UTF8_BYTES = 8192;

    public GraphMetadata {
        if (entries == null) {
            throw new IllegalArgumentException("Graph metadata entries are required");
        }
        if (entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("Graph metadata has too many entries");
        }

        Map<String, String> canonical = new TreeMap<>();
        int totalBytes = 0;
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            String key = normalize(entry.getKey(), "Graph metadata key");
            String value = normalize(entry.getValue(), "Graph metadata value");
            if (key.codePointCount(0, key.length()) > MAX_KEY_CODE_POINTS
                    || value.codePointCount(0, value.length()) > MAX_VALUE_CODE_POINTS) {
                throw new IllegalArgumentException("Graph metadata entry is too long");
            }
            if (!key.matches("[a-z][a-z0-9_.-]*")) {
                throw new IllegalArgumentException("Graph metadata key has an invalid format");
            }
            if (canonical.put(key, value) != null) {
                throw new IllegalArgumentException("Graph metadata contains duplicate canonical keys");
            }
            totalBytes += key.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            totalBytes += value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }
        if (totalBytes > MAX_TOTAL_UTF8_BYTES) {
            throw new IllegalArgumentException("Graph metadata is too large");
        }
        entries = Collections.unmodifiableMap(new LinkedHashMap<>(canonical));
    }

    public static GraphMetadata empty() {
        return new GraphMetadata(Map.of());
    }

    public static GraphMetadata of(Map<String, String> entries) {
        return new GraphMetadata(entries);
    }

    private static String normalize(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return Normalizer.normalize(value, Normalizer.Form.NFC);
    }
}
