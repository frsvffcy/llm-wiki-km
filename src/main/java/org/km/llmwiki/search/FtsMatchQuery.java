package org.km.llmwiki.search;

import java.util.List;
import java.util.stream.Collectors;

/** Converts user text into a bound, literal FTS5 expression without allowing MATCH operators. */
final class FtsMatchQuery {

    /** Maximum raw Unicode code points accepted at the API boundary. */
    static final int MAX_QUERY_CODE_POINTS = 256;
    /**
     * Maximum terms after the versioned projection (AND semantics).
     *
     * <p>A 256-code-point input can expand to 255 overlapping Han bigrams.  A
     * separate, lower projected-term budget keeps the bound on the generated
     * MATCH expression predictable while still allowing normal paragraphs and
     * mixed technical queries (the old pre-projection limit of 16 was too small
     * for ordinary Chinese phrases).
     */
    static final int MAX_PROJECTED_TERMS = 64;

    private FtsMatchQuery() {
    }

    static String literalExpression(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Search query must not be blank");
        }
        String normalized = java.text.Normalizer.normalize(query.strip(), java.text.Normalizer.Form.NFC);
        if (normalized.codePointCount(0, normalized.length()) > MAX_QUERY_CODE_POINTS) {
            throw new IllegalArgumentException("Search query must not exceed "
                    + MAX_QUERY_CODE_POINTS + " Unicode code points");
        }
        if (normalized.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
                && !Character.isWhitespace(codePoint))) {
            throw new IllegalArgumentException("Search query contains unsupported control characters");
        }
        List<String> terms = CjkBigramProjector.tokens(normalized);
        if (terms.isEmpty()) {
            throw new IllegalArgumentException("Search query contains no searchable terms");
        }
        if (terms.size() > MAX_PROJECTED_TERMS) {
            throw new IllegalArgumentException("Search query must not contain more than "
                    + MAX_PROJECTED_TERMS + " terms after projection (projected terms)");
        }
        return terms.stream()
                .map(FtsMatchQuery::quote)
                .collect(Collectors.joining(" AND "));
    }

    private static String quote(String term) {
        return '"' + term.replace("\"", "\"\"") + '"';
    }
}
