package org.km.llmwiki.source;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Controls deterministic cleanup applied after text extraction and before persistence.
 */
@ConfigurationProperties("app.extraction.normalization")
public class ExtractedContentNormalizationProperties {

    private boolean repeatedHeaderFooterEnabled = true;
    private int repeatedHeaderFooterMinimumOccurrences = 2;

    public boolean isRepeatedHeaderFooterEnabled() {
        return repeatedHeaderFooterEnabled;
    }

    public void setRepeatedHeaderFooterEnabled(boolean repeatedHeaderFooterEnabled) {
        this.repeatedHeaderFooterEnabled = repeatedHeaderFooterEnabled;
    }

    public int getRepeatedHeaderFooterMinimumOccurrences() {
        return repeatedHeaderFooterMinimumOccurrences;
    }

    public void setRepeatedHeaderFooterMinimumOccurrences(int repeatedHeaderFooterMinimumOccurrences) {
        if (repeatedHeaderFooterMinimumOccurrences < 2) {
            throw new IllegalArgumentException("Repeated header/footer minimum occurrences must be at least 2");
        }
        this.repeatedHeaderFooterMinimumOccurrences = repeatedHeaderFooterMinimumOccurrences;
    }
}
