package org.km.llmwiki.search;

import java.util.Arrays;
import java.util.stream.Collectors;

/** Converts user text into a bound, literal FTS5 expression without allowing MATCH operators. */
final class FtsMatchQuery {

    private FtsMatchQuery() {
    }

    static String literalExpression(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Search query must not be blank");
        }
        if (query.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
                && !Character.isWhitespace(codePoint))) {
            throw new IllegalArgumentException("Search query contains unsupported control characters");
        }
        return Arrays.stream(query.strip().split("\\s+"))
                .filter(term -> !term.isBlank())
                .map(FtsMatchQuery::quote)
                .collect(Collectors.joining(" AND "));
    }

    private static String quote(String term) {
        return '"' + term.replace("\"", "\"\"") + '"';
    }
}
