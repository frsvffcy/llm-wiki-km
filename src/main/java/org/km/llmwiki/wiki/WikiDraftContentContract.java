package org.km.llmwiki.wiki;

import java.util.List;

/** Explicit expectations a later deterministic Markdown renderer must satisfy. */
public record WikiDraftContentContract(String version, List<String> requiredFrontmatterFields,
                                       List<String> requiredSectionHeadings, boolean evidenceRequired,
                                       boolean unresolvedWikilinksAllowed) {
    public WikiDraftContentContract {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("WikiDraft content contract version must not be blank");
        }
        requiredFrontmatterFields = List.copyOf(requiredFrontmatterFields);
        requiredSectionHeadings = List.copyOf(requiredSectionHeadings);
        if (requiredFrontmatterFields.isEmpty() || requiredSectionHeadings.isEmpty()) {
            throw new IllegalArgumentException("WikiDraft content contract requirements must not be empty");
        }
    }
}
