package org.km.llmwiki.wiki;

import java.text.Normalizer;
import java.util.Locale;

/** Exact, non-path reference accepted by target resolution. */
public record WikiTargetReference(Kind kind, String lookupValue) {

    public enum Kind {
        STABLE_IDENTIFIER,
        CANONICAL_TITLE
    }

    public WikiTargetReference {
        if (kind == null || lookupValue == null || lookupValue.isBlank()) {
            throw new IllegalArgumentException("Wiki target reference requires kind and lookup value");
        }
    }

    public static WikiTargetReference parse(String reference) {
        if (reference == null || reference.isBlank()) {
            throw invalid("MERGE target reference must not be blank");
        }
        String normalized = Normalizer.normalize(reference.strip(), Normalizer.Form.NFC);
        if (normalized.regionMatches(true, 0, "wiki:", 0, "wiki:".length())) {
            String identifier = normalized.substring("wiki:".length());
            if (!identifier.matches("[\\p{L}\\p{N}][\\p{L}\\p{N}._:-]{0,199}")) {
                throw invalid("wiki: reference must contain one exact stable identifier");
            }
            return new WikiTargetReference(Kind.STABLE_IDENTIFIER, identifier);
        }
        if (normalized.length() > 200 || normalized.contains("/") || normalized.contains("\\")
                || normalized.contains("..") || normalized.contains("\0") || normalized.lines().count() != 1) {
            throw invalid("MERGE target title must not be a filesystem path");
        }
        String title = normalizeTitle(normalized);
        return new WikiTargetReference(Kind.CANONICAL_TITLE, title);
    }

    public static String normalizeTitle(String title) {
        if (title == null || title.isBlank()) {
            throw invalid("Wiki target title must not be blank");
        }
        return Normalizer.normalize(title, Normalizer.Form.NFC)
                .strip()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private static WikiTargetResolutionException invalid(String message) {
        return new WikiTargetResolutionException(
                WikiTargetResolutionException.Reason.INVALID_TARGET_REFERENCE, message);
    }
}
