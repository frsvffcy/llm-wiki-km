package org.km.llmwiki.search;

import java.util.Locale;

public enum SearchCorpus {
    WIKI,
    SOURCE,
    ALL;

    static SearchCorpus from(String value) {
        if (value == null || value.isBlank()) {
            return ALL;
        }
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown corpus: " + value
                    + ", allowed: WIKI, SOURCE, ALL");
        }
    }
}
