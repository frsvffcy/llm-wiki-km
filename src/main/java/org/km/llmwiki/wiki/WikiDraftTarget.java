package org.km.llmwiki.wiki;

import java.text.Normalizer;

/** A safe logical create target or an intentionally unresolved reference for STORY-403. */
public record WikiDraftTarget(Kind kind, String logicalRelativePath, String reference) {

    public enum Kind {
        CREATE_NEW,
        EXISTING_REFERENCE
    }

    public WikiDraftTarget {
        if (kind == null) {
            throw new IllegalArgumentException("WikiDraft target kind must not be null");
        }
        switch (kind) {
            case CREATE_NEW -> {
                if (logicalRelativePath == null || logicalRelativePath.isBlank() || reference != null) {
                    throw new IllegalArgumentException("CREATE_NEW target requires only a logicalRelativePath");
                }
                new WikiPathContract().validateLogicalPath(logicalRelativePath);
            }
            case EXISTING_REFERENCE -> {
                if (logicalRelativePath != null || reference == null || reference.isBlank()) {
                    throw new IllegalArgumentException("EXISTING_REFERENCE target requires only a reference");
                }
                reference = validateReference(reference);
            }
        }
    }

    public static WikiDraftTarget createNew(String logicalRelativePath) {
        return new WikiDraftTarget(Kind.CREATE_NEW, logicalRelativePath, null);
    }

    public static WikiDraftTarget existingReference(String reference) {
        return new WikiDraftTarget(Kind.EXISTING_REFERENCE, null, reference);
    }

    private static String validateReference(String value) {
        String normalized = Normalizer.normalize(value.strip(), Normalizer.Form.NFC);
        if (normalized.length() > 200
                || normalized.contains("/")
                || normalized.contains("\\")
                || normalized.contains("..")
                || normalized.contains("\0")
                || normalized.lines().count() != 1) {
            throw new WikiDraftValidationException(WikiDraftValidationException.Reason.UNSAFE_TARGET_REFERENCE,
                    "Merge target must be an unresolved Wiki reference, not a filesystem path");
        }
        return normalized;
    }
}
