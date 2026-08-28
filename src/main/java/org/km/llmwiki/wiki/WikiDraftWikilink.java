package org.km.llmwiki.wiki;

/** A semantic Wiki link; target titles are not interpreted as filesystem paths. */
public record WikiDraftWikilink(String targetTitle, String label) {
    public WikiDraftWikilink {
        if (targetTitle == null || targetTitle.isBlank() || label == null || label.isBlank()) {
            throw new IllegalArgumentException("Wikilink targetTitle and label must not be blank");
        }
        if (targetTitle.contains("/") || targetTitle.contains("\\") || targetTitle.contains("..")) {
            throw new WikiDraftValidationException(WikiDraftValidationException.Reason.UNSAFE_TARGET_REFERENCE,
                    "Wikilink targetTitle must be a semantic title, not a filesystem path");
        }
    }
}
